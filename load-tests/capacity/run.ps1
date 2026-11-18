# Capacity test runner (Windows / PowerShell).
#
#   .\load-tests\capacity\run.ps1 -Scenario read -Mode saturated -Rate 1000 -Hold 60s
#
# Why a runner: a throughput number is only credible when it is published with
# the hardware, the commit, the exact rate schedule and the raw artifacts. This
# script captures all of that next to the k6 output.

param(
    [ValidateSet('health', 'read', 'write', 'waf-clean', 'waf-block', 'mixed')]
    [string]$Scenario = 'read',

    # saturated = raise rate-limit tiers to measure the ceiling.
    # enforced  = production limits; measures contract enforcement under load.
    [ValidateSet('saturated', 'enforced')]
    [string]$Mode = 'saturated',

    [int]$Rate = 500,
    [string]$Hold = '120s',
    [string]$Ramp = '20s',
    [double]$P95Budget = 50,
    [int]$GatewayCpus = 2,
    # Requests that already timed out can keep the gateway busy for up to the
    # k6 timeout after a run ends. Without a settle window the next rate step
    # measures the previous step's backlog instead of its own.
    [int]$Cooldown = 60,
    # Audit sampling multiplies the write volume under test. 1.0 measures the
    # worst case (every request persists); 0.1 shows the steady-state cost.
    [ValidateRange(0.0, 1.0)]
    [double]$AuditSampleRate = 1.0,
    # gcra is the default; sorted-set is kept as the exact-reference path.
    [ValidateSet('gcra', 'sorted-set')]
    [string]$RateLimitAlgorithm = 'gcra',
    # 0 = derive from the mode: unlimited when saturated, 6000 when enforced.
    [int]$AbuseLimitPerIp = 0,
    [switch]$SkipWarmup,
    [switch]$NoRebuild
)

$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
Set-Location $repo

$results = Join-Path $repo 'load-tests\results'
New-Item -ItemType Directory -Force -Path $results | Out-Null

$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$tag = "$Scenario-$Mode-${Rate}rps-$stamp"

# ---- rate-limit tiers -------------------------------------------------------
if ($Mode -eq 'saturated') {
    $env:RATE_HIGH_TRUST = '1000000'
    $env:RATE_MEDIUM_TRUST = '1000000'
    $env:RATE_LOW_TRUST = '1000000'
    if ($AbuseLimitPerIp -le 0) { $AbuseLimitPerIp = 100000000 }
    Write-Host "Mode: saturated (rate limits raised - measuring ceiling)" -ForegroundColor Cyan
}
else {
    $env:RATE_HIGH_TRUST = '50'
    $env:RATE_MEDIUM_TRUST = '30'
    $env:RATE_LOW_TRUST = '10'
    if ($AbuseLimitPerIp -le 0) { $AbuseLimitPerIp = 6000 }
    Write-Host "Mode: enforced (production-rate limits - measuring contract)" -ForegroundColor Cyan
}

$env:RATE_ABUSE_LIMIT_PER_IP = "$AbuseLimitPerIp"
$env:AUDIT_SAMPLE_RATE = "$AuditSampleRate"
$env:RATE_LIMIT_ALGORITHM = $RateLimitAlgorithm
Write-Host ("Admission: algorithm=$RateLimitAlgorithm sample_rate=$AuditSampleRate abuse_per_ip=$AbuseLimitPerIp") -ForegroundColor Cyan

$env:GATEWAY_CPUS = "$GatewayCpus"

# ---- stack ------------------------------------------------------------------
$compose = @(
    '-f', 'docker-compose.yml',
    '-f', 'docker-compose.loadtest.yml'
)

if (-not $NoRebuild) {
    Write-Host 'Building and starting stack (gateway -> sink upstream)...' -ForegroundColor Cyan
    docker compose @compose up -d --build --wait
    if ($LASTEXITCODE -ne 0) { throw "docker compose up failed (exit $LASTEXITCODE)" }
}
else {
    docker compose @compose up -d --wait
    if ($LASTEXITCODE -ne 0) { throw "docker compose up failed (exit $LASTEXITCODE)" }
}

# ---- readiness --------------------------------------------------------------
Write-Host 'Waiting for gateway readiness...' -ForegroundColor Cyan
$ready = $false
for ($i = 0; $i -lt 60; $i++) {
    try {
        $r = Invoke-WebRequest -UseBasicParsing -TimeoutSec 3 `
            -Uri 'http://localhost:8081/actuator/health/readiness'
        if ($r.StatusCode -eq 200) { $ready = $true; break }
    }
    catch { Start-Sleep -Seconds 2 }
}
if (-not $ready) { throw 'Gateway did not become ready. Run: docker compose logs gateway' }

# ---- warmup -----------------------------------------------------------------
if (-not $SkipWarmup) {
    Write-Host 'Warmup (10s, discarded)...' -ForegroundColor DarkGray
    k6 run --quiet `
        -e "SCENARIO=$Scenario" -e 'RATE=100' -e 'RAMP=5s' -e 'HOLD=10s' `
        -e 'P95_BUDGET=10000' -e 'WARMUP=true' `
        -o 'json=load-tests/results/_warmup.json' `
        load-tests/capacity/capacity.js | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "k6 warmup failed (exit $LASTEXITCODE)" }
}

# ---- measured run -----------------------------------------------------------
$jsonOut = "load-tests/results/capacity-$tag.json"
Write-Host "Running $Scenario at ${Rate} req/s for $Hold ..." -ForegroundColor Cyan

k6 run `
    -e "SCENARIO=$Scenario" `
    -e "RATE=$Rate" `
    -e "RAMP=$Ramp" `
    -e "HOLD=$Hold" `
    -e "P95_BUDGET=$P95Budget" `
    -o "json=$jsonOut" `
    load-tests/capacity/capacity.js

$k6Exit = $LASTEXITCODE
# k6 exits 99 when a threshold is crossed. A saturated step is expected to
# cross the latency budget, and that artifact is still a valid result.
# Any other non-zero exit means the run itself failed and must not be
# published as a measurement.
if ($k6Exit -ne 0 -and $k6Exit -ne 99) { throw "k6 measured run failed (exit $k6Exit)" }

$summaryPath = Join-Path $results 'capacity-summary.json'
$summaryTagged = "load-tests/results/capacity-$tag.summary.json"
if (-not (Test-Path $summaryPath)) {
    throw 'k6 produced no summary; refusing to record meta'
}

# ---- settle -----------------------------------------------------------------
if ($Cooldown -gt 0) {
    Write-Host "Cooldown ${Cooldown}s so in-flight requests drain before the next run..." -ForegroundColor DarkGray
    Start-Sleep -Seconds $Cooldown
}

# Rename the summary handleSummary wrote.
Move-Item -Force $summaryPath $summaryTagged

# ---- evidence snapshot ------------------------------------------------------
$dockerStats = docker stats --no-stream --format '{{.Name}}|{{.CPUPerc}}|{{.MemUsage}}'
$metricsSnap = ''
try {
    $metricsSnap = (Invoke-WebRequest -UseBasicParsing -TimeoutSec 5 `
        -Uri 'http://localhost:8081/actuator/prometheus').Content
}
catch { $metricsSnap = 'unavailable' }

$metricsPath = "load-tests/results/capacity-$tag.prometheus.txt"
Set-Content -Path $metricsPath -Value $metricsSnap -Encoding utf8

$gitSha = (git rev-parse HEAD).Trim()
# Results are untracked by design; they must not mark a run as dirty.
$dirtyLines = git status --porcelain -- ':!load-tests/results'
$gitDirty = if ($dirtyLines) { $true } else { $false }

$meta = [ordered]@{
    tag                   = $tag
    scenario              = $Scenario
    mode                  = $Mode
    target_rate           = $Rate
    hold                  = $Hold
    ramp                  = $Ramp
    p95_budget_ms         = $P95Budget
    k6_exit_code          = $k6Exit
    gateway_cpus          = $GatewayCpus
    cooldown_s            = $Cooldown
    rate_limit_algorithm  = $RateLimitAlgorithm
    audit_sample_rate     = $AuditSampleRate
    rate_abuse_limit_per_ip = $AbuseLimitPerIp
    git_sha               = $gitSha
    git_dirty             = $gitDirty
    host_os               = [System.Runtime.InteropServices.RuntimeInformation]::OSDescription
    host_cpus             = [Environment]::ProcessorCount
    docker_version        = (docker version --format '{{.Server.Version}}' 2>$null)
    k6_version            = (k6 version 2>$null)
    started_at            = $stamp
    artifacts             = [ordered]@{
        k6_json = $jsonOut
        summary = $summaryTagged
        metrics = $metricsPath
    }
    container_stats = $dockerStats
}

$metaPath = "load-tests/results/capacity-$tag.meta.json"
$meta | ConvertTo-Json -Depth 5 | Set-Content -Path $metaPath -Encoding utf8

Write-Host ''
Write-Host 'Artifacts:' -ForegroundColor Green
Write-Host "  $jsonOut"
Write-Host "  $summaryTagged"
Write-Host "  $metaPath"
Write-Host "  $metricsPath"
Write-Host ''
Write-Host 'Publish only with the meta.json - a rate without hardware is not a result.' -ForegroundColor Yellow

# Exit code is k6's own code: 0 = latency thresholds met, 99 = a threshold was
# crossed (still a valid, publishable saturated measurement - the summary,
# meta.json and metrics snapshot are written before this point). Any other
# non-zero value means the harness itself failed and no evidence was recorded.
exit $k6Exit
