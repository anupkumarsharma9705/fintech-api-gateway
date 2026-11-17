# Capacity tests

These scripts answer one question the existing `load-tests/*.js` cannot:
**how much traffic can the gateway actually sustain?**

The older scripts are closed-loop with `sleep()` between iterations, so the
achieved request rate is set by the script schedule rather than by the server.
For example, 5 VUs sleeping 1 s produce ~5 req/s no matter how fast the gateway
is. That measures the harness, not the product.

These use k6's `ramping-arrival-rate` executor: the load generator offers a fixed
number of requests per second and records what happens. When the gateway
saturates, latency rises and errors appear instead of the offered rate silently
dropping. That is how you find the knee.

---

## What is measured

| Scenario | Request | What it isolates |
|----------|---------|------------------|
| `health` | `GET /health` | Servlet + Tomcat + JVM ceiling, no gateway logic |
| `read` | `GET /api/account/balance` | Full admission pipeline: trust, rate limit, WAF, risk, proxy |
| `write` | `POST /api/payments/transfer` | Adds idempotency claim + response capture + replay store |
| `waf-clean` | `POST` with a 4 KB benign body | WAF normalisation cost on legitimate traffic |
| `waf-block` | `GET` with SQL injection in the query | WAF must block 100% under load |
| `mixed` | 85% read / 10% write / 5% attack | Realistic blend; exposes interference between paths |

`read`, `write`, `waf-clean` and `mixed` also return `429` when the rate limiter
is enforced. That is a correct contract response, not a failure. Only `5xx` and
connection failures count against `cap_server_error_rate`.

---

## The upstream sink

`docker-compose.loadtest.yml` adds an nginx container that returns a fixed JSON
body for every path and repoints the gateway at it.

This exists because measuring the gateway through a Spring Boot backend mostly
measures the backend. The sink responds in well under a millisecond, so the
latency you see is the gateway's own cost.

The sink is a measurement fixture. It is not part of the deployable product.

---

## Two modes

| Mode | Rate limits | Measures |
|------|-------------|----------|
| `enforced` | 50 / 30 / 10 req/s (production defaults) | That the contract holds under pressure |
| `saturated` | effectively unlimited | The ceiling of the code itself |

Both numbers are worth publishing, clearly labelled. "1,800 req/s sustained with
limits enforced" and "6,400 req/s with admission limits raised" are both true and
neither is misleading on its own.

The runner sets these admission variables explicitly and records them in
`meta.json`, because a throughput number without them is not reproducible:

| Variable | `saturated` | `enforced` |
|----------|-------------|------------|
| `RATE_HIGH_TRUST` / `MEDIUM` / `LOW` | `1000000` | `50` / `30` / `10` |
| `RATE_ABUSE_LIMIT_PER_IP` | `100000000` | `6000` |
| `RATE_LIMIT_ALGORITHM` | `gcra` | `gcra` |
| `AUDIT_SAMPLE_RATE` | `1.0` (override with the param) | `1.0` |

Sampling changes database write volume, not gateway CPU. Run the ladder twice
at `AUDIT_SAMPLE_RATE=1.0` and `0.1` if you want to publish both. Do not mix
rates inside one comparison table.

---

## Running it

### Windows (PowerShell)

```powershell
# Baseline: measure the ceiling on the proxy path
.\load-tests\capacity\run.ps1 -Scenario read -Mode saturated -Rate 1000 -Hold 120s

# Same run with 10% audit sampling
.\load-tests\capacity\run.ps1 -Scenario read -Mode saturated -Rate 1000 -Hold 120s -AuditSampleRate 0.1

# Contract: production limits, verify enforcement holds
.\load-tests\capacity\run.ps1 -Scenario read -Mode enforced -Rate 500 -Hold 60s

# WAF: confirm 100% blocking at rate
.\load-tests\capacity\run.ps1 -Scenario waf-block -Mode saturated -Rate 500 -Hold 60s

# Realistic blend
.\load-tests\capacity\run.ps1 -Scenario mixed -Mode saturated -Rate 800 -Hold 120s
```

### Linux (the publishing environment)

```bash
./load-tests/capacity/run.sh read saturated 1000 120s
AUDIT_SAMPLE_RATE=0.1 ./load-tests/capacity/run.sh read saturated 1000 120s
./load-tests/capacity/run.sh waf-block saturated 500 60s
```

The runners fail loudly if the warm-up fails. k6 v2 removed `--no-summary`, so
`capacity.js` suppresses its own summary when `WARMUP=true`; do not re-add the
flag.

Linux matters. Docker Desktop on Windows routes traffic through a virtual switch
and port mapping, so the numbers are not portable to any real deployment. If you
only have Windows, label every published number as
`Windows 11 / Docker Desktop (WSL2) - not representative of production hardware`.

---

## Finding the knee

Do not run one rate and report it. Step through rates and find where p99 leaves
the budget or errors appear:

```
200 -> 500 -> 1000 -> 2000 -> 4000 -> 8000
```

The knee is the last step where `cap_server_error_rate < 0.1%` **and**
`p99 <= budget`. The sustainable capacity is roughly 70–80% of the knee.

One run per step, 60–120 s hold, after the 10 s warm-up the runner performs.

---

## What gets recorded

Each run writes four files to `load-tests/results/`:

| File | Contents |
|------|----------|
| `capacity-<tag>.json` | Raw k6 samples |
| `capacity-<tag>.summary.json` | Throughput, p50/p95/p99, error rates |
| `capacity-<tag>.meta.json` | Git SHA, dirty flag, OS, CPU count, Docker and k6 versions, rate schedule, limiter algorithm, abuse ceiling, audit sample rate, container CPU/memory |
| `capacity-<tag>.prometheus.txt` | Gateway metrics snapshot, for correlating with JVM and cache state |

**Publish the meta file alongside the number.** A throughput figure without the
hardware and the commit is not reproducible, and an interviewer will ask.

---

## Honest reporting format

```markdown
| Scenario | Rate limits | Achieved | p50 | p95 | p99 | 5xx |
|----------|-------------|----------|-----|-----|-----|-----|
| read     | raised      | 6,180/s  | 2.1 ms | 4.8 ms | 9.3 ms | 0 |
| read     | enforced    | 29/s     | 1.8 ms | 4.1 ms | 7.9 ms | 0 |
| waf-block| raised      | 480/s    | 3.0 ms | 6.2 ms | 11.4 ms | 0 |

Host: 4 vCPU / 8 GB, Ubuntu 24.04, Docker 27.x, commit `abc1234`.
Raw data: `load-tests/results/capacity-*.json`
```

Add one line about what the test does not prove (no TLS termination, no real
backend, single Redis instance). Understating the scope makes the number
credible; overstating it invites exactly the question that kills the claim.

---

## Not covered here

- Business correctness of payments — see `scripts/smoke-test.sh`
- Multi-replica aggregate throughput - Phase 4 scale-out in `docs/SCALING-PLAN.md`
- TLS termination cost — measured at the load balancer, not the gateway
- Sustained soak and memory stability — separate 1-hour run

See `docs/SCALING-PLAN.md` for the bottleneck analysis and the full plan.
