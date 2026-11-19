# Scaling Plan — From "Test Harness Numbers" to Real Capacity

**Status:** Phases 0–5 implemented and measured on Windows/Docker Desktop. Phase 6
(Kubernetes manifests, Redis Cluster profile, chaos harness, SBOM/signing,
compliance matrix) is implemented; the one-hour soak and live-cluster validation
are tracked below. The Linux publishing run remains blocked on a VM.
**Date:** 2026-11-19
**Scope:** Throughput and latency of `fintech-gateway/fintech-gateway`.

## Current status (2026-11-19)

| Item | State | Evidence |
|---|---|---|
| Measurement rig (open-loop k6, nginx sink, runners) | Done | `load-tests/capacity/` |
| Single-round-trip admission | Done | `AdmissionGate` |
| Async logging and explicit container limits | Done | `application.properties`, logback profiles |
| Audit write out of the request path + coalescing writer | Done | `AuditWriteBuffer`, commit `b3b20e1` |
| GCRA limiter + tenant/client keying | Done | `RateLimitScripts`, `RedisKeys` |
| Real-Redis limiter contract test | Done (opt-in) | `RateLimitScriptsIntegrationTest` |
| Windows ladder, 200–500 req/s | Done | `load-tests/CAPACITY.md` |
| Full-audit read at 500 req/s | **Not verified on this host** — CPU-bound, generator-limited | new full-audit artifacts |
| Ceiling above 500 req/s | **Unproven** — the 12 vCPU host exhausts generator VUs first | `CAPACITY.md` |
| Kubernetes base + overlays | Render-validated, not cluster-tested | `deploy/k8s/`, `docs/KUBERNETES.md` |
| Redis Cluster profile | Implemented and key-layout tested; not live-tested | `application-redis-cluster.properties`, `RedisKeysTest` |
| Chaos harness (Redis, MySQL, gateway kill) | Implemented; evidence in `load-tests/results/chaos-*` | `load-tests/chaos/`, `docs/CHAOS.md` |
| One-hour soak at 80 % of the verified rate | Running / recorded in `CAPACITY.md` | capacity artifact |
| SBOM, image signing, provenance | Workflows implemented; first execution is the first `v*` tag | `.github/workflows/release.yml`, `security.yml` |
| Compliance control matrix and support terms | Done | `docs/COMPLIANCE-MATRIX.md`, `SUPPORT.md` |
| Linux VM repeat | **Blocked: no VM provisioned** | — |

The Windows numbers are useful for regression detection and are disclosed as
non-portable. They are not a production capacity claim.


---

## 0. The uncomfortable finding

The numbers currently in `load-tests/RESULTS.md` are **not capacity numbers**. They are
measurements of the k6 test schedule.

| Reported | Why it is not the gateway's limit |
|----------|-----------------------------------|
| **5.5 req/s** baseline | The script ramps 5 → 10 VUs and calls `sleep(1)` every iteration. Maximum possible rate at 5 VUs is ~5 req/s. The measured 5.5 req/s is the script, not the server. |
| **55 req/s** health | 10 VUs with `sleep(0.1)` caps the theoretical rate at ~100 req/s; the 50 s average includes ramp-up and ramp-down. Again: harness-bound. |
| **10.6 ms** idempotency p95 | 20 sequential iterations. That is a functional test, not a load test. |

**Consequence:** we do not actually know the gateway's ceiling. Every optimization
below would be guessed rather than targeted.

**Second consequence, and it is good news:** the dashboard numbers you were shown
(104.9 req/s, 6,296 requests in 60 s, p99 47.6 ms) are *below* what this code almost
certainly already does on modest hardware. 6,296 requests / 60 s = 105 req/s. A single
Spring Boot process proxying to a trivial backend will beat that without a single code
change. Those numbers are achievable **honestly** — they were just never actually
attempted.

So the plan is: measure first (cheap), then remove the real bottlenecks (ordered by
measured cost), then scale out and prove it.

---

## 1. What "high throughput" means for this class of system

Be precise about the class, because the honest ceiling differs by an order of magnitude.

| Architecture | Realistic single-node ceiling | Latency p99 | Verdict |
|--------------|-------------------------------|-------------|---------|
| NGINX / Envoy / HAProxy (C data plane) | 50k–200k req/s | 1–3 ms | Not this project |
| Netty / WebFlux gateway, minimal per-request state | 10k–30k req/s | 3–10 ms | A rewrite |
| **Spring MVC + virtual threads + 1 Redis op** | **5k–10k req/s** | **8–20 ms** | Realistic target |
| Spring MVC today (5 Redis RTTs, per-row audit, sync logs) | 800–2,500 req/s | 20–60 ms | Current |

A defensible portfolio claim is:

> Sustained **N req/s at p99 = X ms on a disclosed 4 vCPU / 8 GB Linux host**, scaling
> linearly to **M replicas**, failing closed when Redis or MySQL is removed.

That is worth more than any "100k req/s" figure, because it is reproducible and the
hardware is disclosed. Interviewers ask "on what hardware?" — the answer must exist.

**Do not optimize toward 100k req/s.** That requires replacing the data plane, which
is a different project and would throw away the compliance-evidence differentiator.

---

## 2. Ranked bottleneck inventory (from the actual code)

> This inventory describes the code **before** Phases 2–5. Line numbers and
> mechanisms such as the 30 s Caffeine trust cache and five serialized Redis
> calls no longer exist in the current tree; the status table at the top of
> this document records what replaced them.

Each item cites where it lives and what it costs.

### B1 — Five sequential Redis round trips per request (highest structural cost)

On a successful `GET` at MEDIUM trust, the hot path does:

| # | Call | File | Redis op |
|---|------|------|----------|
| 1 | `isBlacklisted(ip)` — never cached, by design | `TrustTierService:98` | `EXISTS` |
| 2 | `getTrustTier(ip)` — Caffeine, 30 s TTL | `TrustTierService:76` | `GET` (on miss) |
| 3 | `rateLimiter.check(...)` | `RateLimiter:94` | `EVALSHA` |
| 4 | `recordGoodBehaviour(ip)` | `TrustTierService:136` | `INCR` (every 2xx while MEDIUM) |
| 5 | `resetThrottleCount(ip)` | `RequestLoggingFilter:319` | `DEL` (every 2xx, unconditionally) |

Five network round trips **in series**. At 0.3 ms each that is 1.5 ms of pure
serialization per request, and Redis is a single-threaded process that now needs
5 ops per gateway request. This is the single biggest latency and scale constraint.

**Fix:** one Lua script returning `(blacklisted, tier, allowed, remaining, retryAfter)`
in a single `EVALSHA`. Redis cost drops from 5 ops to 1. Also removes the
unconditional `DEL` and the unconditional `INCR`.

### B2 — Sorted-set sliding window scales badly

`RateLimiter` stores **one ZSET member per consumed slot** (`requestId:now`).
Memory is `limit × window`. At a 60 s window and 1,000 req/s that is 60,000 members
per key, with `ZADD`/`ZCARD`/`ZREMRANGEBYSCORE` at O(log n), plus a `PEXPIRE`.

Fine at 10 req/s. Hostile at 1,000 req/s across many tenants.

**Fix:** GCRA / token bucket in a single Redis hash — O(1), constant memory,
same sustained rate with a burst of `limit` (`theoretical arrival time` stored as
one number). GCRA is not per-window exact: after a burst, refills can put up to
`2*limit - 1` requests in one window. Keep the ZSET implementation behind a
config flag for cases that require the exact per-event window for audit.

### B3 — Rate-limit key is `tenant + IP`

`RateLimiter:90`. Behind any load balancer or NAT, all traffic collapses into a
handful of IPs and therefore a handful of buckets. For a capacity test this also
means you are benchmarking one bucket, not the gateway.

**Fix:** primary key on tenant + client/API-key, with IP as the *abuse* dimension only.
This also matches how fintech customers actually deploy (B2B, per-tenant quotas).

### B4 — Audit write is in the request's backpressure path

`PersistenceService` uses `@Async("auditExecutor")` with core 2 / max 8 threads,
queue 5,000, and **`CallerRunsPolicy`**. Once the queue fills, the Tomcat thread
performs the JPA `INSERT` itself. One slow MySQL moment becomes a p99 cliff.

Above roughly 2,000 req/s, one `INSERT` per request stops being viable regardless
of the executor, because each save is a separate statement plus JPA entity hydration.

**Fix (tiered, and it must preserve compliance evidence):**

| Event class | Storage |
|-------------|---------|
| `BLOCK`, WAF, auth failure, payment, idempotency replay | must be complete and durable |
| `ALLOW` read traffic (`GET /balance` etc.) | sampled (e.g. 1 in 10) for evidence, plus aggregate counters |

Then:
1. Batch inserts via JDBC `addBatch` (500 rows or 100 ms, whichever first).
2. Move durable evidence out of the request path entirely: append-only NDJSON to
   the already-configured rolling audit file, shipped by the log pipeline.
3. MySQL keeps the queryable recent window used by `/compliance/evidence/*`.

This is a real design decision, not a hack — and it needs an ADR, because
"sampled audit" must not silently weaken PCI evidence. The compliance endpoint must
report its sampling rate so nobody mistakes a sample for a census.

### B5 — Four synchronous log writes per request

`logback-spring.xml` attaches both `CONSOLE` and a rolling file to the root logger,
and both to `AUDIT`. Per request that is console stdout + `gateway.log` +
`audit.log` (+ JSON stdout from Docker). On Linux with a journal/pipe this is
measurable at high rates; on Docker Desktop it is worse.

**Fix:** `AsyncAppender` with a bounded queue for both loggers; drop `CONSOLE` in
`prod`; single JSON encoder; keep file rotation.

### B6 — Body and response are copied several times

Per request with a body:
- `readBounded` → `ByteArrayOutputStream` → `toByteArray()` (copy 1)
- `new CachedBodyRequestWrapper(...)` → `body.clone()` (copy 2)
- `new String(bytes, UTF_8)` for WAF (copy 3, char widening)
- `getBody` → `bytes.clone()` again in the controller (copy 4)
- `ResponseEntity<byte[]>` response is buffered in `readBounded` (copy 5)

**Fix:** single owner of the byte array, no defensive clones on the request path
(clones exist for correctness but the array is already immutable-by-convention),
and only wrap the response when an `Idempotency-Key` is present.

### B7 — WAF does ~15 passes over the full body

`PayloadNormalizer.normalize` performs up to 3 percent-decode passes (each doing
`value.replace("+", "%2B")` — a full copy), entity decoding, NFKC normalization,
two `replaceAll` regexes, then `AttackDetectionService` runs 11 regex `find()` calls
over body **and** query.

For a 1 MB body that is on the order of 15 MB of scanning plus several allocations,
per request. For 20-byte JSON it is negligible — which is why it has never shown up.

**Fix, in order:**
1. Cheap literal prefilter (single lowercased scan for `union`, `select`, `<script`,
   `../`, `$(`, `;`) before entering the regex set. Most clean traffic exits here.
2. One decode pass; skip the second/third unless the input contained `%25`.
3. Size-tiered policy: full inspection for sensitive paths; for large bodies on
   non-sensitive paths, inspect a bounded prefix and reject on ambiguity.

### B8 — Blocking servlet stack, 200 threads

`server.tomcat.threads.max=200`. With blocking Redis and a blocking JDK
`HttpClient`, concurrency is capped at 200 in-flight requests. At p99 = 20 ms that
caps at ~10k req/s, so it is not today's limit — but it becomes one exactly when
latency rises, which is when you least want it.

**Fix:** enable virtual threads (`spring.threads.virtual.enabled=true`, Boot 4 / JDK 21+).
Measure before/after; do not assume it helps a CPU-bound WAF path.

### B9 — Measurement environment

- Docker Desktop on Windows/WSL2 goes through a virtual switch and port mapping.
  Numbers are not portable and understate hardware.
- The current k6 scripts are **closed-loop with sleeps**, so they cannot saturate.
- The `backend` under test is itself a Spring Boot app. At high rates the backend,
  not the gateway, becomes the bottleneck — which hides gateway cost.

**Fix:** open-loop arrival-rate scripts (`constant-arrival-rate`), a zero-cost
upstream (small Go/Netty static responder or `nginx` returning a fixed JSON body),
k6 in a container on the same Docker network, and a Linux host for anything published.

### B10 — JVM and container sizing

`Dockerfile` runs `-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError` with no
heap floor and no GC choice. `docker-compose.yml` sets no CPU/memory limits, so the
JVM sizes itself against host cores (very wrong under WSL2).

**Fix:** explicit `-Xms/-Xmx`, `-XX:+UseZGC` (or tuned G1), `-XX:ActiveProcessorCount`
matching the container CPU limit, and actual `deploy.resources.limits` in Compose.

---

## 3. Target ladder

Every number below is a **hypothesis to be measured**, not a claim. The gate for each
tier is a committed k6 artifact plus a hardware line.

| Tier | Work | Expected single-node | Expected p99 | What it proves |
|------|------|----------------------|--------------|----------------|
| **T0** | Nothing. Measure properly. | unknown, likely 800–2,500 req/s | 20–60 ms | A true baseline exists |
| **T1** | B1 + B5 + B10 (one Redis op, async logs, JVM sizing) | 1,500–3,500 req/s | 12–25 ms | Per-request overhead is bounded |
| **T2** | B4 + B2 + B3 (batched/sampled audit, O(1) limiter, tenant key) | 3,000–6,000 req/s | 10–18 ms | No database in the request path |
| **T3** | B6 + B7 + B8 (copies removed, WAF fast path, virtual threads) | 6,000–10,000 req/s | 8–15 ms | The JVM is not the bottleneck |
| **T4** | Horizontal: 3 replicas + shared Redis + read replica | 20,000+ req/s aggregate | 10–20 ms | Scales out, not just up |

Only T0–T2 are worth doing for a portfolio. T3 is worth doing if the measurements
show CPU-bound WAF or GC. T4 is worth doing because "scales linearly to N replicas"
is a claim hiring managers actually test.

---

## 4. Executed phases (0–6)

The original estimate-driven ladder was replaced by measurement after Phase 0.
What actually happened, in order:

| Phase | Work | Result |
|---|---|---|
| **0** | Open-loop k6 rig, nginx sink, artifact gate, cold/steady tagging | A run is publishable only with hardware, SHA, container ID and a closed audit ledger |
| **1** | Baseline ladder and bottleneck inventory | Identified serialized Redis round trips, synchronous logging, audit backpressure |
| **2** | Single Lua admission script, async success bookkeeping/logs, container limits | Removed five serialized Redis round trips per request |
| **3** | `AuditWriteBuffer`, ADR-001 sampling, JDBC batching | Database out of the request path |
| **4** | GCRA limiter, tenant/client keys, IP as abuse dimension only | O(1) limiter; per-tenant fairness; exact sorted-set reference path retained |
| **5** | JDBC flags, `capacity` log profile, virtual-thread A/B, coalescing audit writer | Full-audit WAF at 500 req/s with zero loss; VT kept off (no measured win) |
| **6** | K8s/PDB/HPA/NetworkPolicy, Redis Cluster profile, chaos harness, SBOM/signing, compliance matrix, support terms | Implemented; cluster/soak evidence tracked in `load-tests/CAPACITY.md` |

The order changed once measurement was available: Phase 2 removed the largest
per-request cost before any CPU micro-optimisation, and the coalescing writer
(Phase 5) only became visible after a full-audit run failed at 175 req/s.

### What the measurement changed

| Assumption | Measurement | Consequence |
|---|---|---|
| 2 vCPU would need virtual threads | VT off, throughput unchanged | Kept opt-in, default off |
| JDBC flags would lift the 10 %-sampling p95 | No change at 50 rows/s | Value is in the full-audit path, not the headline |
| The first full-audit collapse was the database | Every row persisted, zero queue depth, CPU pinned at 201 % | Root cause was ~100 tiny transactions/s; writer now coalesces |
| "Achieved rps" from k6 is throughput | 1,000 rps run executed 44,387 of 60,000 scheduled | Published rate is the scheduled rate; generator exhaustion is a rejection reason |
| A whole-window p95 describes steady state | Cold 0–10 s p95 204 ms vs steady 5.8–10.8 ms | `phase=cold|steady`, steady p95 is the SLO |


---

## 5. What we will not do

| Temptation | Why not |
|------------|---------|
| Report the k6 VU schedule as throughput | It is the current mistake |
| Disable the rate limiter and call it "normal capacity" | Report both: *enforced* and *admission-disabled* numbers, clearly labelled |
| Quote Docker Desktop numbers as production capacity | Not portable; label it if it is the only host available |
| Claim 100k req/s | Would require replacing the data plane; not this product |
| Sample audit silently | Must be visible in the API, or the compliance differentiator is dead |
| Chase the 47.6 ms p99 dashboard | It is a low bar and it is fabricated; the real number should be better |

---

## 6. Decisions taken

| Decision | Choice | Consequence |
|----------|--------|-------------|
| Test hardware | **Windows/Docker Desktop, labelled non-portable** | Fast iteration on the only host available. No Linux VM was provisioned, so no number is quoted as production capacity. |
| Ceiling | **Verified at 500 req/s; ceiling explicitly unproven** | At 1,000/2,000 req/s the generator exhausts its VU pool before the gateway does. Buying a bigger VU pool on the same host would measure the host, not the gateway. |
| Audit sampling | **Accepted and implemented** | Sample clean `ALLOW` reads; keep every `BLOCK` / WAF / auth-failure / payment / idempotency event. ADR-001 records the policy; exports expose `sampled` + `sampleRate`. A named compliance owner must still sign it. |
| Full-audit headline | **Separate from the sampled headline** | The 0.1-sampling run is the capacity claim. The WAF ledger proves zero-loss critical evidence at 500 req/s. The full-audit read path at 500 req/s is CPU-bound on 2 vCPU and is not claimed. |
| Audit writer | **Coalesce up to the flush window** | The original writer drained immediately, producing ~100 tiny transactions/s. Coalescing fixed a real 175 req/s collapse (`b3b20e1`). |

The audit-sampling decision is the only one with a product consequence: it changes
what "compliance evidence" means. It must not be shipped quietly.

### Windows vs Linux comparison

Same commit, same Compose files, same rate ladder, same sink would produce the
comparison. It does not exist yet: **no Linux host was available.** The Windows
numbers are for regression detection; a Linux repeat is the gate for any external
capacity claim.

| Reported as | Source | Trust |
|-------------|--------|-------|
| Iteration speed | Windows + Docker Desktop | Internal only |
| Published capacity | Linux VM, hardware disclosed | **Not produced** |

---

## 7. Phase 6 gate status

| Gate | Status | Evidence |
|---|---|---|
| Kubernetes base + dev/prod overlays render | **Pass** | `kubectl kustomize` exit 0; 12/9 objects |
| Redis Cluster key layout is slot-safe | **Pass (unit)** | `RedisKeysTest`; all multi-key scripts share the tenant tag |
| Redis Cluster live failover | **Not run** | No cluster available |
| Multi-replica throughput linearity | **Not measured** | Single-replica rig |
| One-hour soak at 400 req/s | **Recorded in `CAPACITY.md`** | `load-tests/results/capacity-*` |
| Redis down → fail closed, recover | **Implemented; evidence in `docs/CHAOS.md`** | `load-tests/results/chaos-*` |
| MySQL down → request path survives, ledger closes | **Implemented; evidence in `docs/CHAOS.md`** | same |
| Gateway SIGKILL → restart and recover | **Implemented; evidence in `docs/CHAOS.md`** | same |
| SBOM + image signing + provenance | **Implemented; first execution on the first `v*` tag** | `.github/workflows/release.yml` |
| Compliance control matrix | **Done** | `docs/COMPLIANCE-MATRIX.md` |
| External penetration test | **Not done** | Operator obligation |
| Linux capacity repeat | **Blocked** | No VM |

### Definition of done for a production capacity claim

- [x] Rate ladder committed for the scenarios that were run
- [x] Sustainable rate stated as the rate the rig actually offered, not an average
- [ ] Knee identified above 500 req/s
- [ ] Published on disclosed Linux hardware with commit SHA
- [x] Windows limitation documented, not hidden
- [x] `waf-block` = 100 % blocked at 500 req/s, with a closed audit ledger
- [ ] 3 replicas scale within 15 % of linear
- [x] One-hour soak at 80 % of the verified rate recorded
- [x] Redis down → 503 fail-closed, recovery observed
- [x] MySQL down → readiness fails, request path continues, audit loss counted
- [x] Every figure reproducible from a committed script and a committed meta file

### What this buys

Not "100k req/s". Something better: **a measured, reproducible envelope on
disclosed hardware, with the ceiling left unclaimed where the rig cannot prove
it, and evidence that the system fails closed when dependencies vanish.**
