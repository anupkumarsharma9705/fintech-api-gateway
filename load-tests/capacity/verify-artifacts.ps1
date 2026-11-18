# <summary>
# Turns capacity artifacts into per-run evidence, with a publish/reject verdict.
#
#   .\load-tests\capacity\verify-artifacts.ps1 -Filter 'capacity-*-20261118-12*.meta.json'
#   .\load-tests\capacity\verify-artifacts.ps1 -Markdown
#
# Why this exists: three classes of bad number reached the docs before it did.
#
#   1. Cumulative counters were published as per-run values. The Prometheus
#      snapshot is taken after the cooldown, so its counters run for the whole
#      container lifetime. A per-run value MUST be the delta between consecutive
#      snapshots in the same container generation. A decrease means the gateway
#      was recreated (an env change), so the delta resets to the current value.
#   2. "Achieved rps" is requests / whole-run duration, which includes the ramp.
#      At 1000 rps for 60 s the health run executed 73,746 requests, exactly the
#      ramp+hold schedule; the gateway held 1000 rps even though the reported
#      average was 867.6. Published rates must name the scheduled rate and the
#      window.
#   3. A run that exhausts the load generator's VU pool cannot claim a ceiling.
#      At 1000 rps k6 dropped 26,061 iterations with maxVUs = 2000. Those runs
#      are still evidence of collapse, but not of capacity.
#
# A run is publishable only when the tree was clean, k6 exited 0 or 99 (99 = a
# threshold was crossed, which is expected for a saturated step), no status
# outside the scenario allowlist appeared, and the container did not restart
# mid-run.

param(
    [string]$ResultDir = 'load-tests/results',
    [string]$Filter = 'capacity-*.meta.json',
    [switch]$Markdown,
    [switch]$FailOnReject
)

$ErrorActionPreference = 'Stop'
# Artifact numbers are compared across machines and locales. Publish them with
# invariant grouping so "229,418" never turns into "2,29,418".
[Threading.Thread]::CurrentThread.CurrentCulture = [Globalization.CultureInfo]::InvariantCulture

function Get-Metric {
    param([string[]]$Lines, [string]$Name, [hashtable]$Labels = @{})

    foreach ($line in $Lines) {
        if (-not $line.StartsWith($Name + '{')) { continue }
        $close = $line.IndexOf('}')
        if ($close -lt 0) { continue }
        $labelPart = $line.Substring($Name.Length, $close - $Name.Length + 1)

        $match = $true
        foreach ($key in $Labels.Keys) {
            if ($labelPart -notmatch ('{0}="{1}"' -f [regex]::Escape($key), [regex]::Escape([string]$Labels[$key]))) {
                $match = $false
                break
            }
        }
        if (-not $match) { continue }

        $space = $line.LastIndexOf(' ')
        if ($space -lt 0) { continue }
        return [double]::Parse($line.Substring($space + 1), [Globalization.CultureInfo]::InvariantCulture)
    }
    return [double]::NaN
}

function Get-GatewayStats([string]$ContainerStats) {
    $line = ($ContainerStats -split "`n") | Where-Object { $_ -match '-gateway-1\|' } | Select-Object -First 1
    if (-not $line) { return @{ cpu = ''; mem = '' } }
    $parts = $line -split '\|'
    return @{ cpu = $parts[1]; mem = $parts[2] }
}

$metas = Get-ChildItem -Path $ResultDir -Filter $Filter | Sort-Object Name
if (-not $metas) { throw "no meta.json matching '$Filter' under $ResultDir" }

$rows = foreach ($metaFile in $metas) {
    $meta = Get-Content $metaFile.FullName -Raw | ConvertFrom-Json

    $summaryPath = Join-Path $ResultDir (Split-Path $meta.artifacts.summary -Leaf)
    $metricsPath = Join-Path $ResultDir (Split-Path $meta.artifacts.metrics -Leaf)
    if (-not (Test-Path $summaryPath)) { Write-Warning "missing summary for $($metaFile.Name)"; continue }
    if (-not (Test-Path $metricsPath)) { Write-Warning "missing metrics for $($metaFile.Name)"; continue }

    $summary = Get-Content $summaryPath -Raw | ConvertFrom-Json
    $metrics = Get-Content $metricsPath

    $stats = Get-GatewayStats $meta.container_stats
    $sampleRate = [double]$meta.audit_sample_rate

    [pscustomobject]@{
        StartedAt     = [datetime]::ParseExact($meta.started_at, 'yyyyMMdd-HHmmss', $null)
        File          = $metaFile.Name
        Scenario      = $meta.scenario
        Mode          = $meta.mode
        Rate          = [int]$meta.target_rate
        Window        = if ($meta.window) { $meta.window } else { 'ramping' }
        Exit          = [int]$meta.k6_exit_code
        SampleRate    = $sampleRate
        Algorithm     = $meta.rate_limit_algorithm
        Cpus          = [double]$meta.gateway_cpus
        Profiles      = $meta.gateway_profiles
        GitSha        = $meta.git_sha
        Dirty         = [bool]$meta.git_dirty
        Requests      = [int]$summary.requests
        Rps           = [double]$summary.achieved_rps
        ExecutedRatio = if ($null -ne $summary.executed_ratio) { [double]$summary.executed_ratio } else { $null }
        DroppedIters  = if ($null -ne $summary.dropped_iterations) { [int]$summary.dropped_iterations } else { $null }
        GeneratorLimited = [bool]$summary.generator_limited
        P50           = [double]$summary.p50_ms
        P95           = [double]$summary.p95_ms
        P99           = [double]$summary.p99_ms
        ServerErr     = [double]$summary.server_error_rate
        Unexpected    = [double]$summary.unexpected_status_rate
        PersistedCrit = Get-Metric $metrics 'gateway_audit_persisted_total' @{ dataset = 'critical' }
        PersistedSamp = Get-Metric $metrics 'gateway_audit_persisted_total' @{ dataset = 'sampled' }
        DroppedCrit   = Get-Metric $metrics 'gateway_audit_dropped_total' @{ dataset = 'critical'; reason = 'queue_full' }
        DroppedSamp   = Get-Metric $metrics 'gateway_audit_dropped_total' @{ dataset = 'sampled'; reason = 'queue_full' }
        WriteFailCrit = Get-Metric $metrics 'gateway_audit_dropped_total' @{ dataset = 'critical'; reason = 'write_failure' }
        DepthCrit     = Get-Metric $metrics 'gateway_audit_queue_depth' @{ dataset = 'critical' }
        DepthSamp     = Get-Metric $metrics 'gateway_audit_queue_depth' @{ dataset = 'sampled' }
        CpuPost       = $stats.cpu
        MemPost       = $stats.mem
    }
}

# Wall-clock order, not file-name order: the tag embeds the scenario, so name
# order would chain deltas across unrelated container generations.
$rows = @($rows | Sort-Object StartedAt)

$seen = @{}
foreach ($row in $rows) {
    $generation = '{0}|{1}|{2}|{3}|{4}' -f $row.GitSha, $row.SampleRate, $row.Cpus, $row.Mode, $row.Profiles
    $previous = $seen[$generation]

    $reset = $null -ne $previous -and (
        $row.PersistedCrit -lt $previous.PersistedCrit -or
        $row.PersistedSamp -lt $previous.PersistedSamp)

    if ($null -eq $previous) { $kind = 'baseline' }
    elseif ($reset) { $kind = 'reset' }
    else { $kind = 'delta' }

    if ($null -eq $previous -or $reset) {
        $persistedCrit = $row.PersistedCrit
        $persistedSamp = $row.PersistedSamp
        $droppedCrit = $row.DroppedCrit
        $droppedSamp = $row.DroppedSamp
    }
    else {
        $persistedCrit = $row.PersistedCrit - $previous.PersistedCrit
        $persistedSamp = $row.PersistedSamp - $previous.PersistedSamp
        $droppedCrit = $row.DroppedCrit - $previous.DroppedCrit
        $droppedSamp = $row.DroppedSamp - $previous.DroppedSamp
    }

    $row | Add-Member DeltaKind 'baseline' -Force
    $row.PSObject.Properties.Remove('DeltaKind')
    $row | Add-Member NoteProperty DeltaKind $kind
    $row | Add-Member NoteProperty PersistedCritDelta $persistedCrit
    $row | Add-Member NoteProperty PersistedSampDelta $persistedSamp
    $row | Add-Member NoteProperty DroppedCritDelta $droppedCrit
    $row | Add-Member NoteProperty DroppedSampDelta $droppedSamp

    # Ledger: every event is either persisted, dropped, or still queued. For a
    # critical-only fixture (waf-block) this must close against the requests.
    $row | Add-Member NoteProperty AuditLedgerDelta ($persistedCrit + $droppedCrit + $row.DepthCrit)

    $reasons = @()
    if ($row.Dirty) { $reasons += 'git_dirty' }
    if ($row.Exit -ne 0 -and $row.Exit -ne 99) { $reasons += "k6_exit=$($row.Exit)" }
    if ($row.Unexpected -gt 0) { $reasons += "unexpected_status=$($row.Unexpected)" }
    if ($row.ServerErr -gt 0.001) { $reasons += "5xx=$($row.ServerErr)" }
    if ($kind -eq 'reset') { $reasons += 'counter_reset' }

    $row | Add-Member NoteProperty RejectReasons $reasons
    $row | Add-Member NoteProperty Verdict $(if ($reasons.Count -eq 0) { 'PUBLISH' } else { 'REJECT' })
    # Before/after stats are container-lifetime or reset values, not per-run.
    $row | Add-Member NoteProperty Chain $(if ($kind -eq 'delta') { 'delta' } else { $kind })

    $seen[$generation] = $row
}

if ($Markdown) {
    '| Started (local) | Scenario | Rate | Window | Exit | Requests | Generator | p50 ms | p95 ms | p99 ms | 5xx | Unexpected | Audit persisted (crit/samp) | Audit dropped (crit/samp) | Queue after (crit/samp) | Audit chain | Verdict |'
    '|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|'
    foreach ($r in $rows) {
        $generator = if ($r.GeneratorLimited) { "exhausted ($($r.DroppedIters))" } else { 'ok' }
        '{0} | {1} | {2} | {3} | {4} | {5:N0} | {6} | {7:N1} | {8:N1} | {9:N1} | {10:P2} | {11:P2} | {12:N0} / {13:N0} | {14:N0} / {15:N0} | {16:N0} / {17:N0} | {18} | {19} |' -f `
            $r.StartedAt.ToString('yyyy-MM-dd HH:mm'), $r.Scenario, $r.Rate, $r.Window, $r.Exit,
        $r.Requests, $generator, $r.P50, $r.P95, $r.P99, $r.ServerErr, $r.Unexpected,
        $r.PersistedCritDelta, $r.PersistedSampDelta,
        $r.DroppedCritDelta, $r.DroppedSampDelta,
        $r.DepthCrit, $r.DepthSamp, $r.Chain, $r.Verdict
    }
}
else {
    foreach ($r in $rows) {
        $suffix = if ($r.RejectReasons.Count -gt 0) { ' <- ' + ($r.RejectReasons -join ',') } else { '' }
        $generator = if ($r.GeneratorLimited) { " generator_exhausted=$($r.DroppedIters)" } else { '' }
        $chain = if ($r.Chain -ne 'delta') { " audit=$($r.Chain)" } else { '' }
        '{0}  {1,-9} {2,5} rps  sample={3,-3}  {4,-7} exit={5,-2} p95={6,9:N1} ms  persisted={7:N0}/{8:N0} dropped={9:N0}/{10:N0}{11}{12}{13}' -f `
            $r.StartedAt.ToString('yyyy-MM-dd HH:mm'), $r.Scenario, $r.Rate, $r.SampleRate,
        $r.Verdict, $r.Exit, $r.P95, $r.PersistedCritDelta, $r.PersistedSampDelta,
        $r.DroppedCritDelta, $r.DroppedSampDelta, $generator, $chain, $suffix
    }
}

if ($FailOnReject -and ($rows | Where-Object { $_.Verdict -ne 'PUBLISH' })) {
    exit 1
}
