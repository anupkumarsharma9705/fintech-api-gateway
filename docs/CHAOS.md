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
clears any trust state inherited from an earlier WAF capacity run through the
operator endpoint (`POST /feedback/false-positive`), then runs three probes per
fault:

1. **baseline** — normal operation;
2. **fault** — dependency paused/stopped/killed for `FaultSeconds`;
3. **recovery** — dependency restored, readiness awaited, traffic re-probed.

The gateway service runs under `docker-init` (`init: true` in
`docker-compose.yml`). That is what makes the process test honest: a container's
PID 1 ignores unhandled signals, so `kill -9 1` inside the container does
nothing. With an init, the JVM is a child and `pkill -9 -x java` is a real
SIGKILL. `docker kill` is **not** used: it marks the container as manually
stopped, so Docker deliberately does not apply the restart policy.

Artifacts land in `load-tests/results/`:

```
chaos-<stamp>.json                     evidence: checks, phase summaries, snapshots
chaos-<stamp>-<phase>.summary.json     one per k6 probe
```

The top-level `verdict` is `PASS` only when every check passes. The script exits
non-zero on `FAIL` and writes the evidence file even when a probe throws. The
gateway fault probe is capped at 5 s (`gateway_fault_seconds` in the evidence)
because the container restarts in roughly ten seconds; a longer probe
legitimately contains 200s from the new process and would be misread as traffic
admitted during the outage.

## The contract being tested

| Fault | Expected request behaviour | Expected evidence |
|---|---|---|
| **Redis paused** | Admission fails **closed**: 503 with a dependency error. No request is admitted. | Readiness reports the outage; after unpause, 200s return without a restart. |
| **MySQL stopped** | Reads still return 200. The request path has no synchronous database call. | Audit rows are buffered, then persisted or counted as `write_failure`; the ledger closes. Readiness reports the outage; the queue drains after recovery. |
| **Gateway JVM SIGKILL** | Connections are refused until the restart policy starts a new container. | `unreachable > 0` and zero 200s in the 5 s fault probe; readiness returns; the restart count increments; the recovery probe serves 200s. |

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

so rows cannot disappear without a counter.

The snapshot is taken **after** the recovery probe and the controller polls until
the queue is empty. An earlier version snapshotted before the recovery probe but
counted that probe as served, which made 751 correctly persisted rows look
missing; the writer was never at fault. The 95 % floor covers rows produced by
health checks and the small window between the last snapshot and process exit;
inspect `snapshots[].metrics` for exact values.

Sampling is forced to `1.0` for chaos runs, otherwise a sampled-out row would
look like a missing row.

## Last recorded run

| Field | Value |
|---|---|
| Evidence | `load-tests/results/chaos-20261119-223412.json` |
| Commit | `0c620f9` (clean tree) |
| Verdict | **PASS**, 16/16 checks |
| Redis | 451/451 fault responses were 503; readiness 503; recovered 750/750 |
| MySQL | 501/501 requests succeeded during the outage; ledger `1682 persisted + 320 write_failure = 2002 served`; queue 0 |
| Gateway | `sigkill-jvm`; 250/250 fault requests unreachable, zero 200s; restart count 3 → 4; recovered 750/750 |
| Host | Windows 11, Docker Desktop, 12 vCPU |

The recorded run is a correctness result, not a capacity result. The Redis
baseline's p95 in a cold JVM can be several hundred milliseconds; use
[CAPACITY.md](../load-tests/CAPACITY.md) for latency.

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
  probes, PDB and rolling update are render-validated only. A Kubernetes kubelet
  restarts a crashed container without the Compose `docker kill` caveat, but
  that path is not exercised here.
- **A crash under load.** Fault probes run at 50 req/s. The capacity rig does
  not currently kill a replica mid-soak.

## Why fail-closed is the right default

If Redis is unreachable, the gateway cannot know a tenant's quota or whether an
IP is blacklisted. Allowing the request would silently disable the control that
the customer bought. Returning 503 is visible, alertable and safe. The
alternative — fail-open — is only defensible for a non-security proxy, which
this is not.
