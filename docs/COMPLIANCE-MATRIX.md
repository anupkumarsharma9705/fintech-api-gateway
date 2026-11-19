# Compliance control matrix

> **Status: engineering evidence, not a certification.** This document maps the
> gateway's implemented controls to PCI DSS v4.0, the RBI Cyber Security
> Framework / Digital Payment Security Controls, and NPCI-style payment gateway
> expectations. It is deliberately explicit about gaps. A PCI DSS assessment,
> RBI inspection or NPCI certification is performed by an accredited assessor
> against the operator's full environment, not by this repository.

The product's differentiator is not a certificate. It is the ability to produce
**runtime evidence** for controls that are usually attested once a year:
tenant-scoped admission decisions, WAF blocks, idempotency outcomes and audit
integrity. See [ADR-001](adr/ADR-001-audit-sampling.md) for the sampling
decision and its sign-off requirement.

## Evidence sources

| Evidence | Code / artifact | Where an assessor sees it |
|---|---|---|
| Authentication and tenant binding | `security/`, `auth/` | Unit + integration tests; JWT claims mapped to tenant |
| Admission decisions | `admission/AdmissionGate`, `RateLimitScripts` | `gateway_requests_total`, audit rows |
| WAF blocks | `security/waf/` | 403 audit rows, load-test WAF ledger |
| Idempotency | `idempotency/IdempotencyService` | `Idempotency-Replayed` header, audit row |
| Audit sampling disclosure | `audit/AuditSamplingPolicy` | `sampled` + `sampleRate` on every export row |
| Audit durability | `persistence/AuditWriteBuffer` | persisted/dropped counters, ledger tests |
| Evidence export | `compliance/ComplianceController` | CSV/JSON endpoints, attestation digest |
| Schema history | `db/migration/V1..V3` | Flyway `schema_history` |
| Supply chain | `.github/workflows/release.yml`, `target/bom.json` | Signed image + SBOM attestation |

## PCI DSS v4.0

| Requirement | Control in this repository | Status | Gap / operator action |
|---|---|---|---|
| 1.3 Restrict network access to the CDE | Default-deny NetworkPolicy; only ingress-nginx, DNS, MySQL/Redis and upstream egress | **Implemented (manifest)** | Not cluster-tested; managed data stores need `ipBlock` CIDRs |
| 1.4 Network connections between trusted/untrusted | TLS at Ingress; mTLS to upstream optional (`BACKEND_MTLS_*`) | **Partial** | TLS termination is an operator responsibility; no service mesh |
| 2.2 Secure configuration | Env-based config, no secrets in image, read-only rootfs, non-root, dropped capabilities, `seccomp` | **Implemented** | Base-image hardening must be maintained; no CIS benchmark run |
| 3.2/3.4 PAN not stored / protected | Gateway does not persist request bodies; audit stores method, URI, status, tenant, not payloads; body size bounded | **Implemented** | Upstream/backend must not log PAN either; no PAN detection/DLP |
| 4.1 Strong cryptography in transit | HSTS, TLS at Ingress, MySQL `REQUIRE SSL`, optional Redis TLS | **Partial** | Certificate lifecycle and cipher policy are operator-owned |
| 6.2 Secure development | Code review, JUnit/Mockito suite, CI on every push, opt-in real-Redis contract tests | **Implemented** | No mandatory second reviewer on a solo repository |
| 6.3 Vulnerability management | CycloneDX SBOM, Trivy scans, Dependabot, signed releases | **Implemented** | No formal remediation SLA; no external pen test |
| 6.4 Public-facing web app protection | WAF: SQLi, XSS, traversal, command injection, protocol and body-size checks; 100 % block verified under load | **Implemented** | Signature set is static; tune against real traffic |
| 7.2/7.3 Least privilege | OAuth2 scopes, tenant claim binding, separate operator key, no admin UI | **Partial** | No per-endpoint RBAC beyond scopes; no customer-managed roles |
| 8.3/8.4 Authentication | JWT issuer/audience/scope/skew validation; fail-closed IdP outage; ops key lockout | **Partial** | MFA, password policy and user lifecycle belong to the IdP |
| 10.2 Audit logging | Every decision audited; security/payment/idempotency never sampled; WAF blocks retained at 100 % | **Implemented** | Audit rows are mutable by a DB administrator |
| 10.3 Record content | Timestamp, tenant, client, IP, method, URI, risk, decision, status, latency, request id | **Implemented** | No source-port/user-agent field |
| 10.4 Review | `/compliance/evidence/*` with sampling disclosure and digest | **Implemented** | No scheduled reviewer workflow; exports are pull-based |
| 10.5 Retention | None enforced by the gateway | **Gap** | Add partition/retention job (e.g. 12 months hot, 7 years archive) and object-lock storage |
| 10.5.1 Tamper evidence | Per-process salted digest in exports | **Partial** | Not WORM; ship to append-only SIEM or object lock |
| 11.3 Penetration testing | None | **Gap** | External test before any pilot |
| 12.10 Incident response | Runbooks in [OPERATIONS.md](OPERATIONS.md) | **Partial** | No exercised IR plan or on-call rotation |

## RBI Cyber Security Framework (2016) and Digital Payment Security Controls (2021)

| Expectation | Mapping | Status |
|---|---|---|
| Asset inventory and ownership | Container images, SBOM, pinned base images | **Partial** — inventory is generated, ownership is a document |
| Identity and access management | OAuth2/JWT, tenant binding, ops key | **Implemented** for the gateway; IdP remains authoritative |
| Application security | WAF, input bounds, parameterised SQL, dependency scanning | **Implemented** |
| Transaction security | Idempotency, tenant/client quota, abuse ceiling, audit | **Implemented** |
| Fraud and risk monitoring | Trust tiers, throttle/block thresholds, blacklist | **Partial** — rules are deterministic, not ML |
| Log retention and monitoring | Prometheus metrics, audit export | **Partial** — retention and SIEM integration are operator work |
| Incident response and BCP | Failure matrix + chaos tests | **Partial** — no multi-region DR |
| Customer data protection | No payload storage; data minimisation in audit | **Implemented** for the gateway |
| Vendor/third-party risk | SBOM and signed images | **Implemented** for supply chain |

## NPCI-style payment gateway expectations

| Expectation | Mapping | Status |
|---|---|---|
| Unique transaction reference / replay safety | Tenant-scoped idempotency with owner-tokened Lua claim | **Implemented** |
| Duplicate detection under concurrency | Atomic `SET NX` + compare-and-set; contract tests | **Implemented** |
| Rate limiting per participant | Tenant+client quota, tenant+IP abuse ceiling | **Implemented** |
| Complete transaction audit trail | Critical events never sampled; WAF ledger verified | **Implemented** |
| Message signing / non-repudiation | Not in the gateway | **Out of scope** — the upstream payment system owns it |
| UPI-specific message formats | Not implemented | **Out of scope** — this is a generic HTTP gateway |
| Certification with NPCI | Not performed | **Operator obligation** |

## Known gaps that block a compliance claim

1. **No WORM audit store.** MySQL rows are mutable. Until audit events are
   shipped to append-only storage (S3 Object Lock, immutable SIEM index), the
   evidence is tamper-evident only at export time, not at rest.
2. **No retention/enforcement job.** Long-term retention and legal hold are
   undocumented in code.
3. **No external penetration test.** Internal tests are not a substitute.
4. **No formal access review.** Operator key rotation exists; reviewer sign-off
   does not.
5. **Sampling sign-off.** ADR-001 requires a named compliance owner to accept
   that clean `ALLOW` reads are sampled. The code discloses the rate; the
   acceptance is a human decision.
6. **No live Kubernetes/Redis Cluster validation.** Manifests and cluster key
   layout are render/unit validated only.
7. **No DLP or PAN detection.** The gateway avoids storing payloads; it does not
   inspect them for card data.

## How to use this matrix in a sales conversation

Say: *"The gateway produces control evidence at runtime, and here is the matrix
of what it covers and what remains an operator or assessor obligation."* Do not
say: *"PCI compliant"* or *"RBI certified"*. The first is defensible and
differentiating; the second is a claim this repository cannot support.
