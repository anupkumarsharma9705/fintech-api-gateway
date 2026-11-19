# Chaos and failure-contract tests

The capacity rig answers "how much". This harness answers "what happens when a
dependency disappears". It is deliberately separate: a run that returns 503 by
design must not be scored as a latency regression.

## Run

```powershell
# whole suite (Redis, MySQL, gateway kill)
.\load-tests\chaos\chaos.ps1

# one scenario
.\load-tests\chaos\chaos.ps1 -Scenario redis
```

The controller starts the Compose fixture (gateway → nginx sink, MySQL, Redis),
then runs three probes per fault:

1. **baseline** — normal operation;
2. **fault** — dependency paused/stopped/killed for `FaultSeconds`;
3. **recovery** — dependency restored, readiness awaited, traffic re-probed.

Artifacts land in `load-tests/results/`:

```
chaos-<stamp>.json                     evidence: checks, phase summaries, snapshots
chaos-<stamp>-<phase>.summary.json     one per k6 probe
```

The top-level `verdict` is `PASS` only when every check passes. The script exits
non-zero on `FAIL`.

## The contract being tested

| Fault | Expected request behaviour | Expected evidence |
|---|---|---|
| **Redis paused** | Admission fails **closed**: 503 with a dependency error. No request is admitted. | Readiness reports the outage; after unpause, 200s return without a restart. |
| **MySQL stopped** | Reads still return 200. The request path has no synchronous database call. | Audit rows are buffered, then persisted or counted as `write_failure`; the ledger closes. Readiness reports the outage; the queue drains after recovery. |
| **Gateway SIGKILL** | Connections are refused until the restart policy starts a new container. | `unreachable > 0` during the fault, then 200s after readiness. |

This is the same failure matrix described in
[OPERATIONS.md](OPERATIONS.md), now executable.

## What the audit ledger check means

During the MySQL outage the audit writer retries a batch once, then drops it and
increments:

```
gateway_audit_dropped_total{dataset="critical|sampled",reason="write_failure"}
```

That is deliberate: a request that has already been served must not be held
hostage by an unavailable database. The check asserts

```
persisted_delta + write_failure_delta + queue_depth >= 0.95 × successful_probes
```

so rows cannot disappear without a counter. A 95 % floor covers rows produced by
health checks and the small window between the last snapshot and process exit;
inspect `snapshots[].metrics` for exact values.

Sampling is forced to `1.0` for chaos runs, otherwise a sampled-out row would
look like a missing row.

## What this does not prove

- **Network partitions.** Docker pause/stop tests process and connection
  failures, not split-brain, packet loss or asymmetric latency. Use Toxiproxy or
  a real cluster network policy for that.
- **Redis Cluster failover.** A single Redis container is paused. Cluster
  promotion behaviour is untested here.
- **MySQL replication / failover.** Single instance only.
- **Sustained degradation.** Probes run at 50 req/s for 10–15 s. A 1-hour soak
  is separate and lives in [CAPACITY.md](../load-tests/CAPACITY.md).
- **Kubernetes behaviour.** This harness drives Docker Compose; the manifests'
  probes, PDB and rolling update are render-validated only.

## Why fail-closed is the right default

If Redis is unreachable, the gateway cannot know a tenant's quota or whether an
IP is blacklisted. Allowing the request would silently disable the control that
the customer bought. Returning 503 is visible, alertable and safe. The
alternative — fail-open — is only defensible for a non-security proxy, which
this is not.
