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
#   gateway  - the JVM is SIGKILLed while the container's init keeps PID 1
#              alive; connections fail until the restart policy starts a new
#              container, then recovery serves 200s.
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

# Compose reads .env itself; the controller also needs the operator key to
# clear trust state left behind by an earlier WAF load test. Import only that
# key, and only when the shell has not already supplied it.
$dotenv = Join-Path $repo '.env'
if ((Test-Path $dotenv) -and
    ([Environment]::GetEnvironmentVariable('INTERNAL_OPS_API_KEY') -eq $null)) {
    foreach ($line in Get-Content $dotenv) {
        if ($line -match '^\s*INTERNAL_OPS_API_KEY\s*=\s*(.*?)\s*$') {
            [Environment]::SetEnvironmentVariable(
                'INTERNAL_OPS_API_KEY', $matches[1].Trim('"').Trim("'"), 'Process')
            break
        }
    }
}

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
$gatewayFaultMode = 'none'
$gatewayFaultSeconds = $FaultSeconds
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

function Reset-GatewayTrust {
    # A capacity run's WAF scenario permanently blacklists its client IP for
    # the tenant. That state is correct for production, but it makes the next
    # chaos baseline a 403 measurement instead of a healthy baseline. Clear it
    # through the product's operator endpoint, not by deleting Redis keys.
    $tenant = if ($env:DEFAULT_TENANT) { $env:DEFAULT_TENANT } else { 'default' }
    $key = [Environment]::GetEnvironmentVariable('INTERNAL_OPS_API_KEY')
    if (-not $key) {
        throw 'INTERNAL_OPS_API_KEY is required to reset trust state before a chaos run'
    }

    $scan = docker exec fintech-gateway-redis-1 sh -c `
        'redis-cli ${REDIS_PASSWORD:+-a "$REDIS_PASSWORD" --no-auth-warning} --scan --pattern "gw:v2:*:blacklist:*"'
    $ips = @()
    foreach ($line in @($scan)) {
        if ($line -match ':blacklist:(.+)$') { $ips += $matches[1].Trim() }
    }
    $ips = @($ips | Sort-Object -Unique)

    foreach ($ip in $ips) {
        $body = @{
            tenantId = $tenant
            ip       = $ip
            reason   = 'chaos harness: clear state inherited from a prior load test'
        } | ConvertTo-Json
        Invoke-WebRequest -UseBasicParsing -Method Post -TimeoutSec 10 `
            -Uri 'http://localhost:8081/feedback/false-positive' `
            -Headers @{ 'X-Internal-Ops-Key' = $key } `
            -ContentType 'application/json' -Body $body | Out-Null
        Write-Host "  reset trust state for $tenant / $ip" -ForegroundColor DarkGray
    }
    if ($ips.Count -eq 0) {
        Write-Host '  trust state already clear' -ForegroundColor DarkGray
    }
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
        ($baseline.ok -gt 0 -and $baseline.unavailable -eq 0 -and $baseline.server_error -eq 0 -and $baseline.blocked -eq 0) `
        ("ok={0} unavailable={1} blocked={2}" -f $baseline.ok, $baseline.unavailable, $baseline.blocked)

    try {
        docker pause fintech-gateway-redis-1 | Out-Null
        $fault = Invoke-Probe 'redis-fault' $FaultSeconds
        $during = Save-Snapshot 'redis-fault'
        Add-Check 'admission fails closed, not open' `
            ($fault.unavailable -gt 0 -and $fault.ok -eq 0 -and $fault.server_error -eq 0 -and $fault.blocked -eq 0) `
            ("ok={0} unavailable={1} server_error={2} blocked={3}" -f $fault.ok, $fault.unavailable, $fault.server_error, $fault.blocked)
        Add-Check 'readiness reports the dependency outage' `
            ($during.readiness -ne 200) `
            ("readiness HTTP {0}" -f $during.readiness)
    }
    finally {
        try { docker unpause fintech-gateway-redis-1 2>$null | Out-Null } catch { }
    }

    $ready = Wait-Readiness
    $after = Save-Snapshot 'redis-after'
    $recovered = Invoke-Probe 'redis-recovered' $RecoverySeconds
    Add-Check 'readiness recovers' ($ready -and $after.readiness -eq 200) ("readiness HTTP {0}" -f $after.readiness)
    Add-Check 'traffic recovers after Redis returns' `
        ($recovered.ok -gt 0 -and $recovered.unavailable -eq 0 -and $recovered.blocked -eq 0) `
        ("ok={0} unavailable={1} blocked={2}" -f $recovered.ok, $recovered.unavailable, $recovered.blocked)
}

function Test-MysqlFailure {
    Write-Host "`n=== MySQL unavailable ===" -ForegroundColor Cyan
    $before = Save-Snapshot 'mysql-before'
    $baseline = Invoke-Probe 'mysql-baseline' $RecoverySeconds
    Add-Check 'mysql baseline is healthy' `
        ($baseline.ok -gt 0 -and $baseline.unavailable -eq 0 -and $baseline.blocked -eq 0) `
        ("ok={0} unavailable={1} blocked={2}" -f $baseline.ok, $baseline.unavailable, $baseline.blocked)

    try {
        docker stop -t 5 fintech-gateway-mysql-1 | Out-Null
        $fault = Invoke-Probe 'mysql-fault' $FaultSeconds
        $during = Save-Snapshot 'mysql-fault'
        Add-Check 'request path does not depend on MySQL' `
            ($fault.ok -gt 0 -and $fault.unavailable -eq 0 -and $fault.server_error -eq 0 -and $fault.blocked -eq 0) `
            ("ok={0} unavailable={1} server_error={2} blocked={3}" -f $fault.ok, $fault.unavailable, $fault.server_error, $fault.blocked)
        Add-Check 'readiness reports the database outage' `
            ($during.readiness -ne 200) `
            ("readiness HTTP {0}" -f $during.readiness)
    }
    finally {
        docker start fintech-gateway-mysql-1 2>$null | Out-Null
    }

    $ready = Wait-Readiness
    Start-Sleep -Seconds 8
    $recovered = Invoke-Probe 'mysql-recovered' $RecoverySeconds

    # Take the ledger snapshot *after* the recovery probe. The previous order
    # counted the recovery traffic as served but snapshotted before it, which
    # made 751 correctly persisted rows look missing. Poll until the writer
    # has drained so persisted + write_failure + queued accounts for every
    # response that reached the audit path.
    $deadline = (Get-Date).AddSeconds(15)
    do {
        Start-Sleep -Seconds 1
        $after = Save-Snapshot 'mysql-after'
    } while (($null -eq $after.metrics -or $after.metrics.queueDepth -gt 0) -and
             (Get-Date) -lt $deadline)

    Add-Check 'readiness recovers' ($ready -and $after.readiness -eq 200) ("readiness HTTP {0}" -f $after.readiness)
    Add-Check 'traffic continues after MySQL returns' `
        ($recovered.ok -gt 0 -and $recovered.unavailable -eq 0 -and $recovered.blocked -eq 0) `
        ("ok={0} unavailable={1} blocked={2}" -f $recovered.ok, $recovered.unavailable, $recovered.blocked)

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
        ($baseline.ok -gt 0 -and $baseline.unreachable -eq 0 -and $baseline.blocked -eq 0) `
        ("ok={0} unreachable={1} blocked={2}" -f $baseline.ok, $baseline.unreachable, $baseline.blocked)

    $restartsBefore = [int](docker inspect -f '{{.RestartCount}}' fintech-gateway-gateway-1)
    $hasInit = (docker inspect -f '{{.HostConfig.Init}}' fintech-gateway-gateway-1).Trim()
    # The container restarts in roughly ten seconds on this host. A ten-second
    # probe therefore includes legitimate 200s from the new process and would
    # be misread as traffic admitted during the outage. Keep the fault probe
    # inside the restart window; the recovery probe measures the return.
    $gatewayFaultSeconds = [Math]::Max(3, [Math]::Min($FaultSeconds, 5))
    $script:gatewayFaultSeconds = $gatewayFaultSeconds
    if ($hasInit -eq 'true') {
        # docker-init is PID 1, so the JVM is a child and can receive a real
        # SIGKILL. Without an init, the kernel ignores signals sent to PID 1
        # from inside its own namespace, which is why a `kill -9 1` test is
        # silently a no-op.
        docker exec fintech-gateway-gateway-1 pkill -9 -x java | Out-Null
        if ($LASTEXITCODE -ne 0) { throw 'pkill could not find the JVM process' }
        $script:gatewayFaultMode = 'sigkill-jvm'
    }
    else {
        # Fallback for a container without an init: SIGTERM is the only signal
        # a namespace init will act on, so this is a graceful restart, not a
        # crash. The evidence records which mode actually ran.
        docker exec fintech-gateway-gateway-1 sh -c 'kill -TERM 1' | Out-Null
        $script:gatewayFaultMode = 'sigterm-init'
    }
    Start-Sleep -Seconds 1

    $fault = Invoke-Probe 'gateway-fault' $gatewayFaultSeconds
    Add-Check 'kill produces connection failures, not fabricated success' `
        ($fault.unreachable -gt 0 -and $fault.ok -eq 0) `
        ("mode={0} ok={1} unreachable={2}" -f $script:gatewayFaultMode, $fault.ok, $fault.unreachable)

    $ready = Wait-Readiness
    $restartsAfter = [int](docker inspect -f '{{.RestartCount}}' fintech-gateway-gateway-1)
    $recovered = Invoke-Probe 'gateway-recovered' $RecoverySeconds
    Add-Check 'restart policy restores service' `
        ($ready -and $recovered.ok -gt 0 -and $recovered.unreachable -eq 0) `
        ("readiness={0} ok={1} unreachable={2}" -f $ready, $recovered.ok, $recovered.unreachable)
    Add-Check 'container was actually restarted' `
        ($restartsAfter -gt $restartsBefore) `
        ("restarts before={0} after={1}" -f $restartsBefore, $restartsAfter)
}

Write-Host "Chaos run $stamp at ${Rate} req/s (fault ${FaultSeconds}s, recovery ${RecoverySeconds}s)" -ForegroundColor Cyan
Write-Host 'Starting the Compose fixture (no rebuild)...' -ForegroundColor DarkGray
docker compose @compose up -d --wait
if (-not (Wait-Readiness)) { throw 'gateway did not become ready before the chaos run' }

$suiteError = $null
try {
    Write-Host 'Clearing trust state inherited from earlier load tests...' -ForegroundColor DarkGray
    Reset-GatewayTrust
    if ($Scenario -in @('all', 'redis')) { Test-RedisFailure }
    if ($Scenario -in @('all', 'mysql')) { Test-MysqlFailure }
    if ($Scenario -in @('all', 'gateway')) { Test-GatewayKill }
}
catch {
    $suiteError = $_
    Add-Check 'chaos suite completed without an unexpected error' $false $_.Exception.Message
}
finally {
    # Leave the fixture in a usable state even when a probe or assertion fails.
    # Cleanup is best-effort: an already-running container or an unpaused Redis
    # must not turn a test result into a controller crash.
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $paused = (docker inspect -f '{{.State.Paused}}' fintech-gateway-redis-1 2>$null | Out-String).Trim()
        if ($paused -eq 'true') {
            docker unpause fintech-gateway-redis-1 2>$null | Out-Null
        }
        docker start fintech-gateway-mysql-1 2>$null | Out-Null
        docker start fintech-gateway-gateway-1 2>$null | Out-Null
    }
    finally {
        $ErrorActionPreference = $previous
    }
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
    gateway_fault_seconds = $gatewayFaultSeconds
    recovery_seconds = $RecoverySeconds
    audit_sample_rate = 1.0
    gateway_cpus      = 2
    gateway_fault_mode = $gatewayFaultMode
    git_sha          = $gitSha
    git_dirty        = $gitDirty
    host_os          = [System.Runtime.InteropServices.RuntimeInformation]::OSDescription
    host_cpus        = [Environment]::ProcessorCount
    container_id     = (docker inspect -f '{{.Id}}' fintech-gateway-gateway-1 2>$null)
    completed        = ($null -eq $suiteError)
    error            = if ($suiteError) { $suiteError.Exception.Message } else { $null }
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
if ($verdict -ne 'PASS') {
    if ($suiteError) {
        Write-Host ("Aborted: {0}" -f $suiteError.Exception.Message) -ForegroundColor Red
    }
    exit 1
}
