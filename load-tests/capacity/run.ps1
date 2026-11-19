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
    # 0s measures a constant-arrival window, so the reported rate is the
    # offered rate instead of a ramp-diluted average. Pass 20s to reproduce
    # the pre-Phase-5 schedule.
    [string]$Ramp = '0s',
    [double]$P95Budget = 50,
    [int]$GatewayCpus = 2,
    # 0 = derive from the rate. Recorded with the result: a run that exhausts
    # the VU pool measures the generator, not the gateway.
    [int]$MaxVus = 0,
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
    # The tenant whose admission state is reset between scenarios. The load
    # generator sends no tenant header, so this is the gateway default.
    [string]$Tenant = 'default',
    # dev supplies the fixture switch (auth off); capacity removes console log
    # I/O, mirroring the prod logging shape. Override with "dev" to reproduce
    # the pre-Phase-5 logging cost.
    [string]$Profiles = 'dev,capacity',
    [switch]$VirtualThreads,
    [switch]$SkipWarmup,
    # Disable resource sampling entirely. Only for A/B runs that need to prove
    # the sampler is not influencing the latency under test.
    [switch]$NoSampler,
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
# Compose recreates the gateway when these change, which also resets the
# Prometheus counters - the meta records the profile so a counter reset is
# explainable after the fact. CAPACITY_PROFILES, not SPRING_PROFILES_ACTIVE:
# .env owns the latter for real deployments.
$env:CAPACITY_PROFILES = $Profiles
$env:VIRTUAL_THREADS = if ($VirtualThreads) { 'true' } else { 'false' }
Write-Host ("Logging: profiles=$Profiles virtual_threads=$($env:VIRTUAL_THREADS)") -ForegroundColor Cyan

# ---- stack ------------------------------------------------------------------
$compose = @(
    '-f', 'docker-compose.yml',
    '-f', 'docker-compose.loadtest.yml'
)

function Get-TenantTag([string]$Value) {
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try {
        $bytes = [System.Text.Encoding]::UTF8.GetBytes($Value)
        $hex = [System.BitConverter]::ToString($sha.ComputeHash($bytes)).Replace('-', '').ToLowerInvariant()
        return $hex.Substring(0, 16)
    }
    finally {
        $sha.Dispose()
    }
}

function Start-ResourceSampler([string]$OutFile) {
    # The end-of-run `docker stats` snapshot is taken after the cooldown, so it
    # reports idle CPU. Peak CPU during the measured window is the number the
    # acceptance criterion talks about, so sample it while k6 runs.
    #
    # The first version spawned `docker stats --no-stream` every ~1 s. On
    # Docker Desktop each call took 2-10 s, and the loop competed with the
    # gateway for host CPU: at 500 rps it produced 10 s stalls in which p50
    # rose from 2 ms to 15 ms for every request. That is a measurement
    # artifact, not gateway behaviour. One streaming CLI process removes the
    # per-sample spawn; the only recurring cost is one line per second.
    Remove-Item -Force $OutFile -ErrorAction SilentlyContinue
    $errFile = "$OutFile.err"
    Remove-Item -Force $errFile -ErrorAction SilentlyContinue
    return Start-Process -FilePath 'docker' `
        -ArgumentList @('stats', 'fintech-gateway-gateway-1', '--format', '{{.Name}}|{{.CPUPerc}}|{{.MemUsage}}') `
        -RedirectStandardOutput $OutFile `
        -RedirectStandardError $errFile `
        -NoNewWindow -PassThru
}

function Stop-ResourceSampler($Process, [string]$OutFile) {
    if ($null -eq $Process) { return $null }
    if (-not $Process.HasExited) {
        Stop-Process -Id $Process.Id -Force -ErrorAction SilentlyContinue
        Wait-Process -Id $Process.Id -Timeout 5 -ErrorAction SilentlyContinue
    }
    if (-not (Test-Path $OutFile)) { return $null }
    # `docker stats` writes ANSI cursor control even when redirected.
    $esc = [char]27
    $samples = Get-Content $OutFile |
        ForEach-Object { ($_ -replace "$esc\[[0-9;?]*[A-Za-z]", '') -replace "`r", '' } |
        Where-Object { $_ -match '^fintech-gateway-gateway-1\|' }
    if (-not $samples) { return $null }
    $cpu = @(); $mem = @()
    foreach ($s in $samples) {
        $p = $s -split '\|'
        if ($p.Count -lt 3) { continue }
        $cpu += [double]($p[1] -replace '[^0-9.]', '')
        # "516.5MiB / 1GiB" or "1.2GiB / 1GiB"
        $m = [regex]::Match($p[2], '([0-9.]+)\s*(KiB|MiB|GiB)')
        if ($m.Success) {
            $v = [double]$m.Groups[1].Value
            switch ($m.Groups[2].Value) {
                'KiB' { $v = $v / 1024 }
                'GiB' { $v = $v * 1024 }
            }
            $mem += $v
        }
    }
    return [ordered]@{
        samples        = $samples.Count
        cpu_peak_pct   = if ($cpu) { ($cpu | Measure-Object -Maximum).Maximum } else { $null }
        cpu_mean_pct   = if ($cpu) { [math]::Round(($cpu | Measure-Object -Average).Average, 1) } else { $null }
        mem_peak_mib   = if ($mem) { [math]::Round(($mem | Measure-Object -Maximum).Maximum, 1) } else { $null }
    }
}

function Reset-AdmissionState {
    # A capacity scenario must start from a clean admission slate. The
    # waf-block fixture intentionally blacklists the caller after four blocks
    # (24 h block window, permanent blacklist); without this reset every later
    # read from the same IP is a 403 and measures the blacklist, not capacity.
    # Scoped to one tenant: never FLUSHALL, never touch another tenant.
    $tenantTag = Get-TenantTag $Tenant
    $pattern = "gw:v2:{t:$tenantTag}:*"
    Write-Host "Resetting admission state for tenant '$Tenant' ($pattern)..." -ForegroundColor DarkGray
    $script = "redis-cli --scan --pattern '$pattern' | while read -r key; do redis-cli DEL `"`$key`" >/dev/null; done"
    docker compose @compose exec -T redis sh -c $script | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "admission reset failed (exit $LASTEXITCODE)" }
}

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

Reset-AdmissionState

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

# The waf-block warmup deliberately trips the blacklist; the measured run must
# not inherit it.
Reset-AdmissionState

# ---- measured run -----------------------------------------------------------
$jsonOut = "load-tests/results/capacity-$tag.json"
Write-Host "Running $Scenario at ${Rate} req/s for $Hold ..." -ForegroundColor Cyan

$resourceFile = Join-Path ([IO.Path]::GetTempPath()) "capacity-resources-$tag.txt"
if ($NoSampler) {
    Write-Host 'Resource sampler disabled (-NoSampler).' -ForegroundColor Yellow
    $sampler = $null
}
else {
    $sampler = Start-ResourceSampler $resourceFile
}

k6 run `
    -e "SCENARIO=$Scenario" `
    -e "RATE=$Rate" `
    -e "RAMP=$Ramp" `
    -e "HOLD=$Hold" `
    -e "P95_BUDGET=$P95Budget" `
    -e "STEADY_AFTER=10s" `
    -e "MAX_VUS=$MaxVus" `
    -o "json=$jsonOut" `
    load-tests/capacity/capacity.js

$k6Exit = $LASTEXITCODE
# Stop sampling before the cooldown, otherwise idle time is averaged in.
$resources = if ($sampler) { Stop-ResourceSampler $sampler $resourceFile } else { $null }
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

# A status outside the scenario's allowlist means the run measured something
# other than the intended path (a blacklist, a WAF false positive, an upstream
# failure). The k6 thresholds do not fail on that, so the harness must.
$summary = Get-Content $summaryPath -Raw | ConvertFrom-Json
if ($null -eq $summary.unexpected_status_rate) {
    throw 'summary has no unexpected_status_rate; refusing to record meta'
}
if ($summary.unexpected_status_rate -gt 0) {
    throw ("measured run produced unexpected statuses (rate {0}); refusing to record a contaminated artifact" -f $summary.unexpected_status_rate)
}

# A run that ran out of VUs did not offer the load it claims to have offered.
# It is still evidence that the gateway collapsed, but it is not a ceiling
# measurement, so the meta must carry the flag rather than let a reader infer
# a capacity number from a generator-starved run.
if ($summary.generator_limited) {
    Write-Warning ("load generator exhausted its VU pool: {0} dropped iterations, executed {1:P1} of the scheduled load. This run cannot support a ceiling claim." -f $summary.dropped_iterations, $summary.executed_ratio)
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
    # Whole-window p95 includes the cold-start transient. steady_p95_ms is the
    # number the SLO is about; both are recorded so the difference is visible.
    steady_after_s        = $summary.steady_after_s
    steady_p95_ms         = $summary.steady_p95_ms
    cold_p95_ms           = $summary.cold_p95_ms
    k6_exit_code          = $k6Exit
    gateway_cpus          = $GatewayCpus
    cooldown_s            = $Cooldown
    rate_limit_algorithm  = $RateLimitAlgorithm
    audit_sample_rate     = $AuditSampleRate
    rate_abuse_limit_per_ip = $AbuseLimitPerIp
    capacity_tenant       = $Tenant
    admission_reset       = $true
    gateway_profiles      = $Profiles
    # The Prometheus counters live for the container's lifetime, not the git
    # commit's. Keying the delta chain on the container ID keeps a harness-only
    # commit from looking like a counter reset.
    container_id          = (docker inspect -f '{{.Id}}' fintech-gateway-gateway-1 2>$null)
    virtual_threads       = $env:VIRTUAL_THREADS
    max_vus               = $MaxVus
    window                = $summary.window
    scheduled_iterations  = $summary.scheduled_iterations
    executed_ratio        = $summary.executed_ratio
    dropped_iterations    = $summary.dropped_iterations
    generator_limited     = $summary.generator_limited
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
    # Sampled DURING the run, unlike container_stats which is a post-cooldown
    # idle snapshot kept only for memory sanity.
    resources             = $resources
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
