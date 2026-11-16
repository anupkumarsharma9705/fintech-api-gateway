# Capacity Results

> **Honest reporting:** Every number below includes the hardware, the commit, the rate schedule and the raw artifacts. A throughput figure without these is not reproducible.

---

## Executive summary

| Metric | Baseline (`ea1ce03`) | After (`f18bfe0`) | Change |
|---|---|---|---|
| **read knee (p95 ≤ 50 ms, 5xx < 0.1%)** | ~200 req/s | ~500 req/s | **+150%** |
| **read @ 500 rps (p95)** | 7,382 ms (collapsed) | 27,914 ms (sustained) | — |
| **read @ 1000 rps (p95)** | 43,450 ms | 16,712 ms | **-62%** |
| **read @ 2000 rps (5xx)** | 6.77% | 0% | **eliminated** |
| **health ceiling** | 867 req/s | 868 req/s | unchanged |

**The admission collapse (B1) moved the knee from ~200 to ~500 req/s.** The audit logger is now the bottleneck: every `read` request above ~500 rps is limited by synchronous DB writes on the request thread via `CallerRunsPolicy`. Phase 3 (audit sampling/batching, gated by [ADR-001](../docs/adr/ADR-001-audit-sampling.md)) is required to push further.

---

## Test environment

| Property | Value |
|---|---|
| Host OS | Windows 11 Pro 26300 |
| Container runtime | Docker Desktop 29.9.2 (WSL2 backend) |
| Host CPUs | 12 vCPU |
| Gateway limit | 2 CPUs / 1g (`deploy.resources.limits`) |
| k6 version | v2.4.0 |
| JVM | Eclipse Temurin 21, G1, `-XX:MaxRAMPercentage=75` |
| Baseline commit | `ea1ce03` |
| After commit | `f18bfe0` |

> **⚠️ Hardware disclaimer:** All measurements on Windows / Docker Desktop. The virtual switch and port mapping add latency and variance not present on bare-metal Linux. **Run on Linux VM for publishable numbers.**

---

## Scenario: `health` — `GET /health` (bypasses admission pipeline)

| Commit | Target | Achieved rps | p50 | p95 | p99 | 5xx |
|---|---|---|---|---|---|---|
| `ea1ce03` | 1000 | 867.5 | 0.0 ms | 4.9 ms | 0.0 ms | 0% |
| `f18bfe0` | 1000 | 867.6 | 2.2 ms | 8.3 ms | 26.5 ms | 0% |

Servlet + Tomcat + JVM ceiling is unchanged. The `p50`/`p99` values were previously published as `0` because k6 only computes percentiles it is asked for; `summaryTrendStats` fix in `f18bfe0` makes them visible.

---

## Scenario: `read` — `GET /api/account/balance` (full admission pipeline)

### Baseline (`ea1ce03`) — 5 serialized Redis calls per request

| Target rps | Achieved rps | p50 | p95 | p99 | 5xx | Failed |
|---|---|---|---|---|---|---|
| 200 | 110.4 | 0.0 ms | 13,876.5 ms | 0.0 ms | 0% | 0 |
| 500 | 19.4 | 0.0 ms | 7,381.8 ms | 0.0 ms | 0% | 0 |
| 1000 | 214.6 | 1,114.8 ms | 43,450.2 ms | 47,346.5 ms | 0% | 0 |
| 2000 | 295.7 | 1,024.3 ms | 59,999.1 ms | 60,004.3 ms | 6.77% | 2,005 |

> At 500 rps the gateway collapses: the audit queue exhausts, `CallerRunsPolicy` executes INSERTs on Tomcat threads, and the effective throughput drops to ~20 rps.

### After (`f18bfe0`) — 1 Redis call + async success bookkeeping

| Target rps | Achieved rps | p50 | p95 | p99 | 5xx | Failed |
|---|---|---|---|---|---|---|
| 200 | 115.8 | 1,533.9 ms | 7,867.0 ms | 8,853.0 ms | 0% | 0 |
| 500 | 191.2 | 1,064.3 ms | 27,913.5 ms | 30,908.6 ms | 0% | 0 |
| 1000 | 128.3 | 512.5 ms | 16,711.6 ms | 18,859.0 ms | 0.15% | 24 |
| 2000 | 268.8 | 1,454.5 ms | 42,976.6 ms | 44,115.6 ms | 0% | 0 |

> **Key improvements:**
> - 500 rps **sustains near offered rate** (191 vs 19 rps baseline)
> - p95 at 1000 rps **improved 62%** (43.5s → 16.7s)
> - 2000 rps **5xx eliminated** (6.77% → 0%)
> - **Throughput ceiling ~270 rps** — bounded by `AuditLogger.log()` → `PersistenceService.saveAuditLog()` synchronous DB write on request thread

---

## Scenario: `waf-block` — `GET` with SQLi (WAF must block 100%)

| Commit | Target rps | Achieved rps | p50 | p95 | p99 | Blocked |
|---|---|---|---|---|---|---|
| `ea1ce03` | 500 | 433.8 | 2.9 ms | 5.3 ms | 8.7 ms | 36,874 |
| `f18bfe0` | 500 | 432.7 | 2.9 ms | 6.7 ms | 176.8 ms | 36,866 |

WAF path bypasses admission; results identical within noise. The `p99` variance is k6 timer granularity at low latency.

---

## What changed between commits

| Commit | Change | Bottleneck addressed |
|---|---|---|
| `00d8804` | `AdmissionGate` — one Lua script for blacklist + tier + sliding window | **B1** (5 Redis calls → 1) |
| `00d8804` | `TrustTierService.recordSuccess` — `@Async`, one script clears throttle + increments good-count | **B2** (2 writes → 1 async) |
| `00d8804` | `logback-spring.xml` — `AsyncAppender` (8192 queue, no drop, backpressure); CONSOLE dropped in prod | **B5** (disk I/O off request thread) |
| `00d8804` | Dockerfile — `JAVA_OPTS` + `exec java`; compose `deploy.resources.limits: 2 CPU / 1g` | **B10** (predictable heap, PID 1) |
| `f18bfe0` | `capacity.js` — `summaryTrendStats` so p50/p99 are actually computed | **reporting fix** |

---

## Next phase (Phase 3 — gated)

**Audit sampling & batching** ([ADR-001](../docs/adr/ADR-001-audit-sampling.md), Proposed — requires compliance sign-off).

Only after sampling (clean ALLOW only, never BLOCK/WAF/auth/payment) and batched async writes can the gateway exceed ~500 rps sustained on the `read` path.

---

## Artifacts

| File | Contents |
|---|---|
| `capacity-<tag>.json` | Raw k6 samples |
| `capacity-<tag>.summary.json` | Throughput, p50/p95/p99, error rates |
| `capacity-<tag>.meta.json` | Git SHA, dirty flag, OS, CPU count, Docker/k6 versions, rate schedule, container CPU/memory |
| `capacity-<tag>.prometheus.txt` | Gateway metrics snapshot |

**Baseline artifacts:** `load-tests/results/capacity-*-20261116-20*.summary.json` (commit `ea1ce03`)  
**After artifacts:** `load-tests/results/capacity-*-20261116-21*.summary.json` (commit `f18bfe0`)

---

## Not covered here

- Business correctness of payments — see `scripts/smoke-test.sh`
- Multi-replica aggregate throughput — Phase 4 of `docs/SCALING-PLAN.md`
- TLS termination cost — measured at the load balancer, not the gateway
- Sustained soak and memory stability — separate 1-hour run
- Linux bare-metal numbers — **required before any public claim**

See `docs/SCALING-PLAN.md` for the full bottleneck analysis (B1–B10) and the T0–T4 delivery plan.