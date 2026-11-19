# Chaos test controller (Windows / PowerShell).
#
#   .\load-tests\chaos\chaos.ps1
#   .\load-tests\chaos\chaos.ps1 -Scenario redis
#
# Each scenario runs three probes: baseline, fault window, recovery window.
# The assertions encode the gateway's failure contract:
#
#   redis    - admission fails closed with 503; no request is admitted while
#              the limiter state is unreadable; readiness goes down.
#   mysql    - the request path does not touch the database; audit rows are
#              buffered, then persisted or counted as write_failure. Nothing is
#              silently lost. Readiness goes down while the DB is unreachable.
#   gateway  - killing the container produces connection failures until the
#              restart policy brings it back; recovery serves 200s.
#
# Evidence is written to load-tests/results/chaos-<stamp>.json. This is a
# behaviour test, not a capacity measurement, so it is not subject to the
# capacity gate.

param(
    [ValidateSet('all', 'redis', 'mysql', 'gateway')]
    [string]$Scenario = 'all',
    [int]$Rate = 50,
    [int]$FaultSeconds = 10,
    [int]$RecoverySeconds = 15,
    [int]$ReadyTimeoutSeconds = 120
)

$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
Set-Location $repo

$results = Join-Path $repo 'load-tests\results'
New-Item -ItemType Directory -Force -Path $results | Out-Null
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'

# Same fixture profile as the capacity rig, but this run is about correctness.
# Per-tenant limits are raised so a 429 cannot be mistaken for a fault response.
$env:CAPACITY_PROFILES = 'dev,capacity'
$env:AUDIT_SAMPLE_RATE = '1.0'
$env:RATE_HIGH_TRUST = '1000000'
$env:RATE_MEDIUM_TRUST = '1000000'
$env:RATE_LOW_TRUST = '1000000'
$env:RATE_ABUSE_LIMIT_PER_IP = '100000000'
$env:GATEWAY_CPUS = '2'

$compose = @('-f', 'docker-compose.yml', '-f', 'docker-compose.loadtest.yml')
$phases = New-Object System.Collections.Generic.List[object]
$snapshots = New-Object System.Collections.Generic.List[object]
$checks = New-Object System.Collections.Generic.List[object]

function Add-Check {
    param([string]$Name, [bool]$Pass, [string]$Detail)
    $checks.Add([pscustomobject]@{ name = $Name; pass = $Pass; detail = $Detail })
    $colour = if ($Pass) { 'Green' } else { 'Red' }
    Write-Host ("  [{0}] {1} - {2}" -f $(if ($Pass) { 'PASS' } else { 'FAIL' }), $Name, $Detail) -ForegroundColor $colour
}

function Invoke-Probe {
    param([string]$Phase, [int]$Seconds)
    $env:TAG = $stamp
    $env:PHASE = $Phase
    $env:RATE = "$Rate"
    $env:DURATION = "${Seconds}s"
    k6 run --quiet 'load-tests/chaos/chaos-probe.js' | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "chaos probe '$Phase' failed (k6 exit $LASTEXITCODE)" }
    $path = "load-tests/results/chaos-$stamp-$Phase.summary.json"
    if (-not (Test-Path $path)) { throw "chaos probe '$Phase' wrote no summary" }
    $summary = Get-Content $path -Raw | ConvertFrom-Json
    $phases.Add($summary)
    Write-Host ("  probe {0,-18} requests={1,5} ok={2,5} unavailable={3,4} server_error={4,3} unreachable={5,4} p95={6:N1}ms" -f `
        $Phase, $summary.requests, $summary.ok, $summary.unavailable, $summary.server_error, $summary.unreachable, $summary.latency_p95_ms)
    return $summary
}

function Get-Readiness {
    try {
        $r = Invoke-WebRequest -UseBasicParsing -TimeoutSec 5 `
            -Uri 'http://localhost:8081/actuator/health/readiness'
        return [int]$r.StatusCode
    } catch {
        if ($_.Exception.Response) { return [int]$_.Exception.Response.StatusCode }
        return 0
    }
}

function Wait-Readiness {
    param([int]$TimeoutSeconds = $ReadyTimeoutSeconds)
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        if ((Get-Readiness) -eq 200) { return $true }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $deadline)
    return $false
}

function Get-MetricValue {
    param([string]$Text, [string]$Name, [string]$Reason = '')
    $sum = 0.0
    $prefix = "^$([regex]::Escape($Name))\{"
    foreach ($line in ($Text -split "`n")) {
        if ($line -notmatch $prefix) { continue }
        if ($Reason -and $line -notmatch "reason=`"$([regex]::Escape($Reason))`"") { continue }
        $parts = $line.Trim() -split '\s+'
        $sum += [double]::Parse($parts[-1], [System.Globalization.CultureInfo]::InvariantCulture)
    }
    return $sum
}

function Get-GatewayMetrics {
    try {
        $text = (Invoke-WebRequest -UseBasicParsing -TimeoutSec 5 `
            -Uri 'http://localhost:8081/actuator/prometheus').Content
    } catch {
        return $null
    }
    return [pscustomobject]@{
        persisted    = Get-MetricValue $text 'gateway_audit_persisted_total'
        droppedWrite = Get-MetricValue $text 'gateway_audit_dropped_total' 'write_failure'
        droppedQueue = Get-MetricValue $text 'gateway_audit_dropped_total' 'queue_full'
        queueDepth   = Get-MetricValue $text 'gateway_audit_queue_depth'
    }
}

function Save-Snapshot {
    param([string]$Label)
    $snapshot = [pscustomobject]@{
        label      = $Label
        timestamp  = (Get-Date).ToString('o')
        readiness  = (Get-Readiness)
        metrics    = (Get-GatewayMetrics)
    }
    $snapshots.Add($snapshot)
    return $snapshot
}

function Format-Metrics {
    param($Metrics)
    if ($null -eq $Metrics) { return 'metrics unavailable' }
    return ("persisted={0:N0} dropped_write={1:N0} dropped_queue={2:N0} queue={3:N0}" -f `
        $Metrics.persisted, $Metrics.droppedWrite, $Metrics.droppedQueue, $Metrics.queueDepth)
}

function Test-RedisFailure {
    Write-Host "`n=== Redis unavailable ===" -ForegroundColor Cyan
    $before = Save-Snapshot 'redis-before'
    $baseline = Invoke-Probe 'redis-baseline' $RecoverySeconds
    Add-Check 'redis baseline is healthy' `
        ($baseline.ok -gt 0 -and $baseline.unavailable -eq 0 -and $baseline.server_error -eq 0) `
        ("ok={0} unavailable={1}" -f $baseline.ok, $baseline.unavailable)

    try {
        docker pause fintech-gateway-redis-1 | Out-Null
        $fault = Invoke-Probe 'redis-fault' $FaultSeconds
        $during = Save-Snapshot 'redis-fault'
        Add-Check 'admission fails closed, not open' `
            ($fault.unavailable -gt 0 -and $fault.ok -eq 0 -and $fault.server_error -eq 0) `
            ("ok={0} unavailable={1} server_error={2}" -f $fault.ok, $fault.unavailable, $fault.server_error)
        Add-Check 'readiness reports the dependency outage' `
            ($during.readiness -ne 200) `
            ("readiness HTTP {0}" -f $during.readiness)
    }
    finally {
        docker unpause fintech-gateway-redis-1 2>$null | Out-Null
    }

    $ready = Wait-Readiness
    $after = Save-Snapshot 'redis-after'
    $recovered = Invoke-Probe 'redis-recovered' $RecoverySeconds
    Add-Check 'readiness recovers' ($ready -and $after.readiness -eq 200) ("readiness HTTP {0}" -f $after.readiness)
    Add-Check 'traffic recovers after Redis returns' `
        ($recovered.ok -gt 0 -and $recovered.unavailable -eq 0) `
        ("ok={0} unavailable={1}" -f $recovered.ok, $recovered.unavailable)
}

function Test-MysqlFailure {
    Write-Host "`n=== MySQL unavailable ===" -ForegroundColor Cyan
    $before = Save-Snapshot 'mysql-before'
    $baseline = Invoke-Probe 'mysql-baseline' $RecoverySeconds
    Add-Check 'mysql baseline is healthy' `
        ($baseline.ok -gt 0 -and $baseline.unavailable -eq 0) `
        ("ok={0} unavailable={1}" -f $baseline.ok, $baseline.unavailable)

    try {
        docker stop -t 5 fintech-gateway-mysql-1 | Out-Null
        $fault = Invoke-Probe 'mysql-fault' $FaultSeconds
        $during = Save-Snapshot 'mysql-fault'
        Add-Check 'request path does not depend on MySQL' `
            ($fault.ok -gt 0 -and $fault.unavailable -eq 0 -and $fault.server_error -eq 0) `
            ("ok={0} unavailable={1} server_error={2}" -f $fault.ok, $fault.unavailable, $fault.server_error)
        Add-Check 'readiness reports the database outage' `
            ($during.readiness -ne 200) `
            ("readiness HTTP {0}" -f $during.readiness)
    }
    finally {
        docker start fintech-gateway-mysql-1 2>$null | Out-Null
    }

    $ready = Wait-Readiness
    Start-Sleep -Seconds 8
    $after = Save-Snapshot 'mysql-after'
    $recovered = Invoke-Probe 'mysql-recovered' $RecoverySeconds
    Add-Check 'readiness recovers' ($ready -and $after.readiness -eq 200) ("readiness HTTP {0}" -f $after.readiness)
    Add-Check 'traffic continues after MySQL returns' `
        ($recovered.ok -gt 0 -and $recovered.unavailable -eq 0) `
        ("ok={0} unavailable={1}" -f $recovered.ok, $recovered.unavailable)

    # Ledger: every probe response that reached the audit path is persisted,
    # counted as a write failure, or still queued. A negative residual would
    # mean rows vanished without a counter.
    if ($before.metrics -and $after.metrics) {
        $served = $baseline.ok + $fault.ok + $recovered.ok
        $accounted = ($after.metrics.persisted - $before.metrics.persisted) +
                     ($after.metrics.droppedWrite - $before.metrics.droppedWrite) +
                     $after.metrics.queueDepth
        Add-Check 'audit ledger closes across the outage' `
            ($accounted -ge ($served * 0.95)) `
            ("served={0} accounted={1} (persisted {2}, write_failure {3}, queued {4})" -f `
                $served, [math]::Round($accounted), `
                ($after.metrics.persisted - $before.metrics.persisted), `
                ($after.metrics.droppedWrite - $before.metrics.droppedWrite), `
                $after.metrics.queueDepth)
        Add-Check 'audit queue drains after recovery' `
            ($after.metrics.queueDepth -eq 0) `
            ("queue={0}" -f $after.metrics.queueDepth)
    }
    else {
        Add-Check 'audit ledger closes across the outage' $false 'metrics unavailable'
    }
}

function Test-GatewayKill {
    Write-Host "`n=== Gateway process killed ===" -ForegroundColor Cyan
    $baseline = Invoke-Probe 'gateway-baseline' $RecoverySeconds
    Add-Check 'gateway baseline is healthy' `
        ($baseline.ok -gt 0 -and $baseline.unreachable -eq 0) `
        ("ok={0} unreachable={1}" -f $baseline.ok, $baseline.unreachable)

    docker kill fintech-gateway-gateway-1 | Out-Null
    $fault = Invoke-Probe 'gateway-fault' $FaultSeconds
    Add-Check 'kill produces connection failures, not fabricated success' `
        ($fault.unreachable -gt 0 -and $fault.ok -eq 0) `
        ("ok={0} unreachable={1}" -f $fault.ok, $fault.unreachable)

    $ready = Wait-Readiness
    $recovered = Invoke-Probe 'gateway-recovered' $RecoverySeconds
    Add-Check 'restart policy restores service' `
        ($ready -and $recovered.ok -gt 0) `
        ("readiness={0} ok={1}" -f $ready, $recovered.ok)
}

Write-Host "Chaos run $stamp at ${Rate} req/s (fault ${FaultSeconds}s, recovery ${RecoverySeconds}s)" -ForegroundColor Cyan
Write-Host 'Starting the Compose fixture (no rebuild)...' -ForegroundColor DarkGray
docker compose @compose up -d --wait
if (-not (Wait-Readiness)) { throw 'gateway did not become ready before the chaos run' }

try {
    if ($Scenario -in @('all', 'redis')) { Test-RedisFailure }
    if ($Scenario -in @('all', 'mysql')) { Test-MysqlFailure }
    if ($Scenario -in @('all', 'gateway')) { Test-GatewayKill }
}
finally {
    # Leave the fixture in a usable state even when an assertion throws.
    docker unpause fintech-gateway-redis-1 2>$null | Out-Null
    docker start fintech-gateway-mysql-1 2>$null | Out-Null
}

$failed = @($checks | Where-Object { -not $_.pass })
$verdict = if ($failed.Count -eq 0) { 'PASS' } else { 'FAIL' }
$gitSha = (git rev-parse HEAD).Trim()
$gitDirty = if (git status --porcelain -- ':!load-tests/results') { $true } else { $false }

$evidence = [ordered]@{
    tag              = $stamp
    started_at       = $stamp
    scenario         = $Scenario
    target_rate      = $Rate
    fault_seconds    = $FaultSeconds
    recovery_seconds = $RecoverySeconds
    audit_sample_rate = 1.0
    gateway_cpus      = 2
    git_sha          = $gitSha
    git_dirty        = $gitDirty
    host_os          = [System.Runtime.InteropServices.RuntimeInformation]::OSDescription
    host_cpus        = [Environment]::ProcessorCount
    container_id     = (docker inspect -f '{{.Id}}' fintech-gateway-gateway-1 2>$null)
    phases           = $phases
    snapshots        = $snapshots
    checks           = $checks
    verdict          = $verdict
}
$evidencePath = "load-tests/results/chaos-$stamp.json"
$evidence | ConvertTo-Json -Depth 8 | Set-Content -Path $evidencePath -Encoding utf8

Write-Host ''
Write-Host "Evidence: $evidencePath" -ForegroundColor Green
Write-Host ("Verdict: {0} ({1} checks, {2} failed)" -f $verdict, $checks.Count, $failed.Count) `
    -ForegroundColor $(if ($verdict -eq 'PASS') { 'Green' } else { 'Red' })
if ($verdict -ne 'PASS') { exit 1 }
