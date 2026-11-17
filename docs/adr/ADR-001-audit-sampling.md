# ADR-001: Audit sampling and batching

- **Status:** Accepted for implementation (2026-11-17). Production or pilot use still requires a named compliance-owner sign-off on the control list below.
- **Date:** 2026-11-16
- **Deciders:** Platform owner, compliance owner
- **Affects:** `AuditLogger`, `AuditWriteBuffer`, `PersistenceService`, `/compliance/**`, retention policy

## Context

Every proxied request currently produces one audit event. The event is written
to the `AUDIT` logger and persisted to the database through a bounded async
executor. That is correct and cheap at low volume, but at the T4 target
(20k req/s aggregate sustained) it is the largest remaining write amplification
in the system:

- 20,000 events/s is ~1.7 billion rows/day before indexes.
- The database becomes the bottleneck long before the gateway does.
- Most of those rows are `ALLOW`/2xx records with no security value beyond
  aggregate traffic counts, which Prometheus already holds.

At the same time, dropping audit records is not free: PCI-DSS 10.2, RBI
cyber-security guidance and NPCI operating rules all require a demonstrable
trail for security-relevant and payment-relevant events. A sampling policy that
silently discards evidence is worse than no sampling at all, because it creates
false confidence.

## Decision

1. **Sample only clean ALLOW traffic.**
   - Sampled: `decision = ALLOW`, `status < 400`, no attack type,
     no idempotency interaction, not a payment path.
   - Never sampled (always persisted at 100%):
     - every `BLOCK` decision (WAF, risk, blacklist, throttled, rate limited,
       invalid tenant, method not allowed);
     - every authentication/authorization failure (401/403 from the auth layer);
     - every `SECURITY_DEPENDENCY` / `GATEWAY_ERROR` failure;
     - every payment or transfer path (`/api/payments/**`);
     - every request carrying an `Idempotency-Key`, including replays and
       in-progress conflicts;
     - every trust-tier mutation (blacklist, downgrade, upgrade, operator reset).
2. **Sampling is deterministic per request, not random per write.**
   The decision is derived from a stable hash of the request id, so a retried
   or replayed request lands on the same side of the sample and the audit trail
   is internally consistent.
3. **Sampled-out events still increment metrics.**
   `gateway.requests.total`, latency histograms and status counters are updated
   for every request. Only the durable per-event row is skipped.
4. **The sample rate is visible, not implicit.**
   - `/compliance/summary` and every `/compliance/*` response include
     `"sampled": true` and `"sampleRate": <n>` when sampling is active.
   - The export itself states that the evidence is sampled.
   - Startup logs the effective rate once at INFO.
5. **Default is 100% (no sampling).**
   Sampling is opt-in via `AUDIT_SAMPLE_RATE` (0.0–1.0). A value of `1.0` or an
   unset value preserves today's behaviour exactly. The `prod` profile refuses
   to start with a rate below `0.01`, because near-zero sampling is
   indistinguishable from turning audit off.
6. **Batching is storage-side, not decision-side.**
   The write path buffers sampled events and flushes them in bounded batches
   (size and interval configurable). A failed flush is retried once, then
   downgraded to a `AUDIT_WRITE_FAILURE` metric and a loud log line; a failed
   flush must never fail the request, which has already been served.

## Guarantees this preserves

- **No silent loss of security evidence.** The never-sampled list above covers
  every event a compliance auditor or incident responder would ask for.
- **No request-path regression.** Sampling is an integer comparison on data
  already in memory; batching moves work off the request thread. Both are
  covered by unit tests asserting the request path performs no extra Redis or
  database call.
- **No false attestation.** Because `sampled` and `sampleRate` are exposed on
  every compliance response and export, an evidence bundle can never be
  mistaken for a complete trail.

## Explicit non-goals

- This does not make the gateway compliant on its own. It makes the audit
  volume tractable while keeping the evidence set defensible; control mapping,
  retention, sign-off and external attestation remain operator obligations.
- This does not sample application logs (`gateway.log`), which keep their
  existing retention and are not part of the evidence export.

## Alternatives considered

| Alternative | Why rejected |
| --- | --- |
| Keep 100% of events and scale the database | Cost grows linearly with traffic and makes the gateway's capacity claim depend on database sizing, not gateway code. |
| Sample everything uniformly | Would lose blocks, auth failures and payment events — the exact records that matter. |
| Log-only audit (no database) | Loses queryable evidence and the compliance export; log rotation becomes the retention policy. |
| Probabilistic sampling with no visibility flag | Produces evidence that looks complete but is not. Rejected on integrity grounds. |

## Consequences

- Compliance exports must carry the sampling disclaimer; this changes the
  response schema (additive fields only).
- Load tests must record the sample rate alongside throughput, because sampling
  changes database load, not gateway latency.
- The retention policy can now be expressed in days of *sampled* volume rather
  than raw request volume.

## Implementation notes (2026-11-17)

The decision above is implemented in the gateway:

- `AuditSamplingPolicy` implements the deterministic request-id sample and the
  never-sample list. The default remains `AUDIT_SAMPLE_RATE=1.0`.
- `AuditWriteBuffer` owns two bounded queues (critical and sampled) and one
  background writer. The request thread only calls a non-blocking `offer`; a
  full queue drops and counts the event instead of blocking the Tomcat thread.
- Batches are written with `JdbcTemplate.batchUpdate` (default 500 rows or
  100 ms). A failed batch is retried once; a repeated failure increments
  `gateway.audit.write.failures` and `gateway.audit.dropped{reason=write_failure}`
  and emits one loud error line.
- `/compliance/evidence/summary` and `/compliance/evidence/audit.csv` expose
  `sampled`/`sampleRate`; the CSV adds `sample_rate` and `dataset_sampled`, and
  the response carries `X-Evidence-Sampled` / `X-Evidence-Sample-Rate`.
- The production floor of `0.01` is enforced only in the `prod` profile.

Measured effect is recorded in `load-tests/CAPACITY.md`. Sampling at `1.0` and
`0.1` is measured separately, because sampling changes database write volume,
not gateway CPU cost.

## Sign-off required before a production or pilot deployment

This ADR is accepted for implementation in this repository. It is **not** an
attestation that the policy satisfies any specific regime until the compliance
owner confirms:

1. the never-sampled list is complete for the target regimes (PCI-DSS, RBI,
   NPCI);
2. a 100% sample rate remains the default for any pilot customer;
3. the `sampled` / `sampleRate` fields and export wording are acceptable as the
   disclosure mechanism.

Until that sign-off is recorded, a pilot or production deployment must keep
`AUDIT_SAMPLE_RATE=1.0` and treat the sampling path as measured but unapproved.
