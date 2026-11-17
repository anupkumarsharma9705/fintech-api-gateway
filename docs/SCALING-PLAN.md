# Scaling Plan — From "Test Harness Numbers" to Real Capacity

**Status:** In progress. Phases 0–2 are implemented and measured on
Windows/Docker Desktop; the Linux publishing run and Phase 4 scale-out are open.
**Date:** 2026-11-16 (status updated 2026-11-17)
**Scope:** Throughput and latency of `fintech-gateway/fintech-gateway`.

## Current status (2026-11-17)

| Item | State | Evidence |
|---|---|---|
| Measurement rig (open-loop k6, nginx sink, runners) | Done | `load-tests/capacity/` |
| Single-round-trip admission | Done | `AdmissionGate`, commit `00d8804` |
| Async logging and explicit container limits | Done | commit `00d8804` |
| Audit write out of the request path | Done | `AuditWriteBuffer`, ADR-001 |
| GCRA limiter + tenant/client keying | Done | `RateLimitScripts`, `RedisKeys` |
| Real-Redis limiter contract test | Done (opt-in) | `RateLimitScriptsIntegrationTest` |
| Windows capacity ladder after Phases 2–4 | Done | `load-tests/CAPACITY.md` |
| Linux VM repeat of the ladder | **Blocked: no VM provisioned** | — |
| Multi-replica, soak, chaos | **Not started** | — |

The Windows numbers are useful for regression detection and are disclosed as
non-portable. They are not the published capacity claim.

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
same exact per-request semantics (`theoretical arrival time` stored as one number).
Keep the current ZSET implementation behind a config flag if exactness per-event is
ever needed for audit.

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

## 4. Execution phases

### Phase 0 — Build the measurement rig (blocking, ~1 day)

1. `load-tests/upstream-sink/` — a minimal Go (or `nginx`) service returning a fixed
   JSON body in <1 ms. Replaces the Spring Boot backend for capacity tests.
2. New scripts, all **open-loop**:
   - `cap-health.js` — `/health`, arrival rate ramp 100 → 20,000/s
   - `cap-admission.js` — clean GET through the full pipeline, rate limit disabled
   - `cap-proxy.js` — full pipeline + upstream call
   - `cap-waf-clean.js` — 4 KB JSON body, must stay clean
   - `cap-waf-block.js` — attack payloads, must stay 100% blocked at rate
   - `cap-idempotent.js` — unique keys, measures acquire + complete cost
   - `cap-mixed.js` — 80/15/5 read/write/attack mix
3. A runner that records, per step: rps, p50/p95/p99, HTTP error rate, gateway CPU %,
   Redis ops/s, MySQL rows/s, GC pause p99, and the exact git SHA + host specs.
4. `load-tests/CAPACITY.md` generated from the artifacts — never hand-typed.

**Exit:** a curve of throughput vs. latency with a visible knee, on recorded hardware.

### Phase 1 — Remove serialized waits (~2–3 days)

- B1: single Lua script for blacklist + tier + limit + good-behaviour.
- B5: async, non-console logging in prod.
- B10: explicit JVM sizing and container limits.

**Exit:** Redis ops/request = 1 in a profile capture; T1 throughput measured.

### Phase 2 — Get the database out of the request path (~3–4 days)

- B4: sampled audit for `ALLOW` reads, complete audit for security/payment events,
  JDBC batch flush, NDJSON as the durable evidence stream.
- ADR: `docs/ADR/000X-audit-sampling.md` — because this touches compliance evidence.
- `/compliance/evidence/*` must expose `sampled: true|false` and the sample rate.
- B2: GCRA limiter behind `gateway.rate-limit.algorithm=gcra|sorted-set`.
- B3: tenant-keyed limiting with IP as abuse dimension.

**Exit:** MySQL write rate flat as rps rises; T2 measured.

### Phase 3 — Shave the CPU path (only if Phase 2 says it matters)

- B6: single-copy body handling; capture responses only for idempotent requests.
- B7: literal prefilter, single decode, size-tiered inspection.
- B8: virtual threads A/B test.

**Exit:** flame graph / async-profiler shows no single method above ~10% of CPU.

### Phase 4 — Scale out and prove failure behaviour

- 3 gateway replicas behind the sink/LB, shared Redis, MySQL.
- Soak: 1 hour at 70% of the knee; heap stable, no FD leak.
- Chaos: stop Redis → 503 fail-closed, recovery < 10 s. Stop MySQL → readiness down,
  traffic drains, audit buffer does not lose security events (or loses them loudly).
- Publish aggregate throughput and the linearity factor.

### Phase 5 — Publish

`load-tests/CAPACITY.md` with: host specs, commit SHA, k6 config, per-scenario table,
raw JSON, the knee point, and an explicit "what this does not prove" section.

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
| Test hardware | **Both, compared** | Windows/Docker Desktop for fast iteration, always labelled non-portable. Every published number comes from a Linux VM. |
| Ceiling | **T4 — 20k+ req/s aggregate** | Full ladder: Phase 0 → 1 → 2 → 3 → 4, including multi-replica proof, soak and chaos. |
| Audit sampling | **Accepted and implemented** | Sample `ALLOW` reads, keep every `BLOCK` / WAF / payment / idempotency event. ADR-001 records the policy; compliance responses expose `sampled` + `sampleRate`. A named compliance owner must still sign the control list before production/pilot use. |

The audit-sampling decision is the only one with a product consequence: it changes
what "compliance evidence" means. It must not be shipped quietly.

### Windows vs Linux comparison

Same commit, same Compose files, same rate ladder, same sink. Expect the Linux
numbers to be materially higher and lower-variance. Publish both and explain the
gap rather than hiding it — the comparison itself demonstrates measurement rigour.

| Reported as | Source | Trust |
|-------------|--------|-------|
| Iteration speed | Windows + Docker Desktop | Internal only |
| Published capacity | Linux VM, hardware disclosed | Citable |

---

## 7. Delivery plan

Each line is a reviewable unit with a gate that must pass before the next starts.

| # | Change | Gate |
|---|--------|------|
| **0** | **Measurement rig** — `capacity.js`, nginx sink, Compose overlay, Windows/Linux runners | Rig runs and produces a full artifact set. *Done.* |
| **1** | Baseline run: rate ladder on Windows, then Linux | `load-tests/CAPACITY.md` with a real curve and a knee, meta files committed |
| **2** | B1 — single Lua script for blacklist + tier + limit + good-behaviour | Redis ops/request = 1 (verified by `INFO commandstats`); T1 delta measured. *Implemented and measured.* |
| **3** | B5 + B10 — async logging, no console in prod, explicit JVM sizing, container limits | No log write on the request thread; GC pause p99 recorded before/after. *Implemented and measured.* |
| **4** | ADR for audit sampling, then B4 — batched inserts, sampled `ALLOW` reads, NDJSON evidence stream | MySQL rows/s flat as rps rises; `/compliance` reports `sampled` + rate. *Implemented; NDJSON stream is not implemented.* |
| **5** | B2 + B3 — GCRA limiter behind a flag, tenant-keyed buckets | Same enforcement semantics proven by test; Redis memory constant vs window. *Implemented; real-Redis contract test added.* |
| **6** | B6 + B7 — single-copy bodies, capture only for idempotent requests, WAF literal prefilter + single decode | `waf-clean` throughput up; `waf-block` still 100% at rate |
| **7** | B8 — virtual threads A/B | Keep only if measured win; otherwise document why not |
| **8** | Linux VM: repeat the full ladder, publish | Every number carries host specs + commit SHA |
| **9** | Phase 4 — 3 replicas, soak 1 h, chaos (Redis down, MySQL down) | Linear scaling factor recorded; fail-closed behaviour proven under load |
| **10** | Publish `CAPACITY.md` + README update + profile card | No unsupported claim anywhere in the repo |

### Definition of done for T4

- [ ] Rate ladder committed for `health`, `read`, `write`, `waf-clean`, `waf-block`, `mixed`
- [ ] Knee identified; sustainable capacity stated as 70–80% of it
- [ ] Published on disclosed Linux hardware with commit SHA
- [ ] Windows vs Linux delta documented, not hidden
- [ ] `waf-block` = 100% blocked at the sustained rate, with request counts
- [ ] 3 replicas scale within 15% of linear
- [ ] 1-hour soak: heap stable, no FD growth, p99 drift < 10%
- [ ] Redis down → 503 fail-closed, recovery < 10 s, no security event lost
- [ ] MySQL down → readiness fails, traffic drains, audit buffer behaviour documented
- [ ] Every figure reproducible from a committed script and a committed meta file

### What this buys

Not "100k req/s". Something better: **a measured, reproducible capacity envelope
with disclosed hardware, and a system that fails closed when dependencies vanish.**
That is what a platform interviewer actually probes, and it is the one claim most
candidates cannot back up.
