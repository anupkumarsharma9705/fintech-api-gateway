# Operations Runbook

This runbook covers the single-node Docker Compose stack and the operational
behavior of the gateway itself. Adapt the deployment commands to your
orchestrator; the health semantics and failure rules stay the same.

No SLOs are asserted here. Measure your own p95/p99 latency, error budget and
recovery time with the load tests and metrics before promising numbers to
customers.

---

## 1. Service map

| Service | Container port | Host default | Notes |
|---|---:|---:|---|
| Gateway | 8081 | `127.0.0.1:8081` | Public entrypoint; TLS proxy expected in front |
| Backend | 8080 | not published | Reachable only on `fintech-network` |
| MySQL | 3306 | `127.0.0.1:3307` | Audit/trust store; Flyway owns the schema |
| Redis | 6379 | `127.0.0.1:6379` | Rate limits, idempotency, trust state |
| Authorization server | external | — | Issues client-credentials JWTs; the gateway fetches its JWKS |

Both Java containers run as UID 1001 with a read-only root filesystem, a 64 MB
`/tmp` tmpfs and all Linux capabilities dropped. The gateway writes logs to the
`gateway_logs` volume; MySQL and Redis use named volumes.

## 2. Startup and shutdown

### Start

```bash
cp .env.example .env      # first time only
docker compose up --build -d
docker compose ps
```

Startup order is enforced by health checks: MySQL and Redis must be healthy,
then the backend, then the gateway. The gateway's Flyway migration runs before
the HTTP listener accepts traffic. When authentication is enabled, startup also
requires `OAUTH2_ISSUER_URI` or `OAUTH2_JWK_SET_URI`; a missing verification
source is a startup failure, not a degraded mode.

Expected startup evidence:

```bash
docker compose logs -f gateway | grep -E 'Flyway|Started FintechGatewayApplication|Tomcat started'
curl -fsS http://localhost:8081/health
curl -fsS http://localhost:8081/actuator/health/readiness
curl -fsS http://localhost:8081/actuator/prometheus | head
```

If the gateway exits at startup, check for
`gateway.auth.enabled=true requires gateway.auth.issuer-uri or
gateway.auth.jwk-set-uri` before changing anything else. The `dev` profile is
the only one that disables authentication by default.

### Stop

```bash
docker compose stop gateway     # SIGTERM; graceful shutdown up to 30s
docker compose down             # keep volumes
docker compose down -v          # DESTROYS MySQL/Redis/log data
```

The gateway has `server.shutdown=graceful` and a 30 s shutdown phase. The audit
executor waits up to 20 s for queued writes. Do not use `docker kill` for routine
operations; it can drop queued audit rows.

## 3. Health semantics

| Endpoint | Checks | Use for |
|---|---|---|
| `GET /health` | Nothing; returns `{"status":"UP","service":"fintech-gateway"}` | Simple external ping |
| `GET /actuator/health/liveness` | Process state only | Container liveness probe |
| `GET /actuator/health/readiness` | `readinessState`, MySQL, Redis | Load-balancer readiness / deploy gate |
| `GET /actuator/health` | All contributors | Debugging only; details are hidden |

**Never use readiness as a liveness probe.** A Redis or MySQL outage should
remove an instance from rotation, not restart a healthy process. Restarting the
gateway does not fix a dependency and can amplify the outage.

## 4. Failure matrix

| Client sees | Likely cause | Gateway state | First action |
|---|---|---|---|
| 503 `security_dependency_unavailable` | Redis unreachable | Liveness UP, readiness DOWN | Check `docker compose ps redis`; restore Redis; confirm readiness |
| 503 `rate_limiter_unavailable` | Redis error/timeout during blacklist, trust-tier or rate-limit admission | Liveness UP, readiness DOWN | Same; inspect Redis latency and memory. The code is retained for client-contract stability even though it now also covers the trust store |
| 503 `trust_store_unavailable` | Redis failed during an operator trust reset | Gateway healthy | Retry the reset; inspect Redis. Automatic penalties fail soft instead |
| 503 `upstream_unavailable` | Backend down, connect/read timeout | Gateway healthy | Check backend health and `BACKEND_URL`; check network |
| 502 `bad_gateway` | Backend returned an unreadable response | Gateway healthy | Inspect backend logs and response framing |
| 502 `upstream_response_too_large` | Response exceeded `MAX_UPSTREAM_RESPONSE_BYTES` | Gateway healthy | Confirm the endpoint should return that much data; raise the limit only with a memory review |
| 429 `rate_limited` / `throttled` | Window exhausted or THROTTLE budget exceeded | Gateway healthy | Inspect `X-RateLimit-*`, trust tier and tenant/IP key |
| 403 `access_denied` | IP is blacklisted | Gateway healthy | Verify the block, then use the false-positive procedure |
| 403 `request_rejected` | WAF signature matched | Gateway healthy | Check `gateway.waf.blocked` and the audit row's `attack_type`; tune only with evidence |
| 401 `invalid_token` | Missing, expired, badly signed or wrong-audience Bearer token | Gateway healthy | Decode the token; check issuer, audience, expiry and `gateway.auth.failures{reason}` |
| 403 `invalid_token_claims` | Tenant claim missing or outside `TENANT_PATTERN` | Gateway healthy | Check the IdP's `tenant_id` claim mapping; do not relax the pattern without review |
| 403 `tenant_mismatch` | `X-Tenant-Id` differs from the authenticated tenant | Gateway healthy | Remove the header or correct the client; never resolve this by trusting the header |
| 403 `insufficient_scope` | Token lacks a required scope | Gateway healthy | Check `OAUTH2_REQUIRED_SCOPES` and the IdP client grant; the challenge names the scope |
| 503 `auth_dependency_unavailable` | JWKS/network/IdP failure | May be UP if cached keys still work | Check IdP reachability and `OAUTH2_JWK_SET_URI`; the gateway fails closed |
| 401 `unauthorized` | Missing/invalid operator key | Gateway healthy | Check `INTERNAL_OPS_API_KEY` in the gateway environment |
| 429 `too_many_attempts` | Operator auth lockout | Gateway healthy | Wait for `OPS_LOCKOUT_WINDOW`, or delete `opsauth:fail:<ip>` after verifying the caller |
| 400 `invalid_time_range` / `future_time_range` / `range_too_large` | Compliance window is invalid or too wide | Gateway healthy | Use ISO-8601 instants with `from < to`; narrow the window or raise `COMPLIANCE_MAX_WINDOW` deliberately |
| 400 `invalid_limit` | Compliance CSV `limit` is not positive | Gateway healthy | Pass a positive integer; the configured maximum still applies |
| 413 `payload_too_large` | Body over `MAX_BODY_BYTES` | Gateway healthy | Confirm the client payload; raise the limit only with a memory/latency review |
| 415 `unsupported_content_encoding` | Request used `Content-Encoding` other than `identity` | Gateway healthy | Ask the client to send plaintext; the WAF cannot inspect compressed bodies |

### MySQL outage

Requests continue to be proxied because audit writes are asynchronous and
best-effort. Readiness goes DOWN and the audit table stops receiving rows until
MySQL returns. The structured `AUDIT` log line is still emitted, so preserve and
ship stdout during the outage. When the audit queue (capacity 5,000, 2–8
threads) fills, `CallerRunsPolicy` applies backpressure to request threads and
latency rises; this is deliberate but should page an operator before it happens.

### Redis outage

Admission fails closed for every non-internal path. `/health` and
`/actuator/health/liveness` still return 200; readiness returns DOWN. Restoring
Redis clears in-flight rate windows and, depending on persistence/failover, may
lose recent idempotency records and trust changes. **A lost idempotency record
means the gateway can no longer suppress a duplicate payment.** The backend must
keep its own idempotency defense until Redis durability is proven for your
deployment.

### Identity-provider / JWKS outage

The gateway verifies signatures locally after fetching the authorization
server's JWKS. With a warm key cache, existing clients may keep working during a
short outage; tokens signed with a key the gateway has not cached fail. JWKS
fetch and network failures are classified as `AUTH_DEPENDENCY_UNAVAILABLE` and
return 503 — never 401/403 (which would blame the client) and never an
unauthenticated pass-through.

Readiness does not probe the IdP. Correlate a spike in
`gateway.auth.failures{reason="AUTH_DEPENDENCY_UNAVAILABLE"}` with IdP status
and DNS/TLS from the gateway network. Do not disable authentication to restore
traffic; fix or fail over the IdP. Rotated signing keys require the gateway to
fetch the new JWK set, so publish the new key before signing with it.

## 5. Common procedures

### Report a false positive and reset trust

```bash
curl -sS -X POST http://localhost:8081/feedback/false-positive \
  -H 'Content-Type: application/json' \
  -H "X-Internal-Ops-Key: $INTERNAL_OPS_API_KEY" \
  -d '{"ip":"203.0.113.5","reason":"support ticket 1234"}'
```

The reset deletes `blacklist:`, `blockcount:`, `throttlecount:`, `goodcount:`
and `trust:` for the IP, invalidates the local tier cache, restores MEDIUM and
writes a `trust_history` row. Verify:

```sql
SELECT ip, previous_tier, new_tier, reason, timestamp
FROM trust_history WHERE ip = '203.0.113.5'
ORDER BY timestamp DESC LIMIT 5;
```

### Rotate the operator key

1. Generate a new value (`openssl rand -hex 32`).
2. Update the secret in the deployment environment, not in git.
3. Roll the gateway (`docker compose up -d gateway`).
4. Update operator tooling, the evidence-export job and the demo UI input.
5. Verify an old key gets 401 on both `/feedback/**` and `/compliance/**`, and
   the new key succeeds.

The key is read at startup; there is no hot reload. The failed-attempt counter
is per caller IP and does not reset on rotation.

### Export compliance evidence

Use an operator host with the current key. The window is bounded by
`COMPLIANCE_MAX_WINDOW` (default 90 days) and the CSV by `COMPLIANCE_MAX_ROWS`
(default 10,000).

```bash
FROM=$(date -u -d '24 hours ago' +%Y-%m-%dT%H:%M:%SZ)
TO=$(date -u +%Y-%m-%dT%H:%M:%SZ)

curl -fsS "http://localhost:8081/compliance/evidence/summary?from=$FROM&to=$TO" \
  -H "X-Internal-Ops-Key: $INTERNAL_OPS_API_KEY" | jq .

curl -fsS -D headers.txt \
  "http://localhost:8081/compliance/evidence/audit.csv?from=$FROM&to=$TO&limit=5000" \
  -H "X-Internal-Ops-Key: $INTERNAL_OPS_API_KEY" \
  -o "audit-evidence-$FROM.csv"

grep -iE 'x-evidence-(rows|truncated)|cache-control' headers.txt
```

- `X-Evidence-Truncated: true` means the row cap was hit. The endpoint has no
  cursor; split the window into smaller intervals and export each one.
- `wafDetections` counts only the four WAF signature families
  (`SQL_INJECTION`, `XSS`, `PATH_TRAVERSAL`, `COMMAND_INJECTION`). Rate limits,
  replays, method rejections, blacklist blocks and auth failures appear in
  `attackTypes` but are not WAF detections.
- Every CSV field is quoted and cells beginning with `=`, `+`, `-` or `@` are
  prefixed with an apostrophe. Do not strip that neutralization when opening or
  post-processing the file.
- Confirm the export itself was audited:

```sql
SELECT timestamp, ip, client_id, uri, status_code
FROM audit_logs
WHERE attack_type = 'COMPLIANCE_EXPORT'
ORDER BY timestamp DESC LIMIT 5;
```

- Store the export under the evidence retention policy and record who requested
  it. A summary is runtime evidence, not an attestation of PCI-DSS/RBI/NPCI
  compliance.

### Rotate the IdP signing key

1. Publish the new public key in the JWKS **before** the authorization server
   signs tokens with it.
2. Confirm the gateway can fetch the updated JWKS. Key caching is internal; an
   unknown `kid` triggers a refresh, but allow time for the new key to
   propagate before switching production traffic.
3. Switch signing to the new key.
4. Keep the old public key in the JWKS until the maximum token lifetime plus
   clock skew has passed.
5. Watch `gateway.auth.failures` during the change; a spike in
   `AUTHENTICATION_FAILED` after rotation usually means the new key was not
   published first.

The gateway holds no client secrets and does not mint or revoke tokens. Token
lifetime and revocation are IdP responsibilities. Changing the issuer URI or
the JWK set URI requires a gateway restart; all auth configuration is read at
startup.

### Deploy a new version

```bash
git pull
docker compose build gateway
docker compose up -d --no-deps gateway
curl -fsS http://localhost:8081/actuator/health/readiness
```

Flyway migrations are forward-only and validated at startup. Back up MySQL
before a release that includes a migration. Never edit an already-applied
migration; add a new `V<n>__*.sql`.

Rollback: redeploy the previous image tag. If the release applied a schema
migration, roll back the application only after confirming the old code is
compatible with the new schema; otherwise restore the database backup.

### Scale out

The gateway is stateless apart from Redis and MySQL, so run as many instances as
needed behind a load balancer. Do not enable sticky sessions; idempotency and
rate limiting are atomic in Redis across instances. Size the shared
dependencies first:

- Redis: `maxmemory-policy noeviction` (already set in Compose). Evicting
  security state would fail open or lose idempotency; add memory or shard
  instead.
- MySQL: Hikari is 20 connections per instance by default. Multiply by the
  instance count and stay below the server's `max_connections`; raise
  `DB_POOL_MAX` only with evidence.
- Audit executor: 2–8 threads and a 5,000-task queue per instance. Watch the
  queue and DB write latency.

## 6. Backup and restore

### MySQL

```bash
docker compose exec -T mysql \
  mysqldump --single-transaction --routines --triggers \
  -u root -p"$MYSQL_ROOT_PASSWORD" "$MYSQL_DB" > "gateway-$(date +%F).sql"
```

Back up daily, retain according to your audit policy, and enable binary logs if
you need point-in-time recovery. Test a restore into a separate database at
least quarterly. The audit and trust tables are evidence; a backup that has
never been restored is not a backup.

### Redis

Compose runs Redis with AOF (`appendonly yes`). Copy the `redis_data` volume or
use `BGREWRITEAOF`/`SAVE` plus a volume snapshot. Redis state is reconstructible
for rate limits but not for completed idempotency records; treat its loss as a
duplicate-payment risk and reconcile with the backend.

### Logs

The `gateway_logs` volume holds `audit.log` and `gateway.log` with the retention
configured in `logback-spring.xml` (audit 90 days/20 GB, application 30
days/5 GB). Ship stdout and the audit file to a SIEM or immutable store. A
single Docker volume is not an archive and does not survive host loss.

## 7. Monitoring starting points

Alert on symptoms, not just resource usage:

- `gateway.security.dependency.failure` > 0 for 2 minutes — admission is failing
  closed.
- `gateway.upstream.errors` rising — backend or network problem.
- 5xx ratio and `gateway.request.duration` p95/p99 — user-visible degradation.
- `gateway.rate.limited` spike — attack, misconfigured client, or limits too low.
- `gateway.idempotency.store.failure` > 0 — a successful payment could not be
  recorded for replay. The client still received the upstream response and the
  claim stays reserved; investigate Redis before the TTL expires.
- `gateway.idempotency.release.failure` > 0 — a failed operation's claim could
  not be released. The key stays reserved until its TTL; the client still
  received the upstream error.
- `gateway.waf.blocked{threat}` spike — attack campaign or false-positive
  regression after a rule change.
- `gateway.auth.failures{reason}` spike — expired/incorrect client credentials,
  a missing scope, or an IdP/JWKS problem. 401/403 points at the client or
  configuration; 503 points at the dependency.
- `gateway.compliance.export{type}` — unexpected volume or exports outside
  operator hours mean audit data is being accessed; correlate with
  `attack_type = 'COMPLIANCE_EXPORT'`.
- Readiness DOWN while liveness UP — dependency outage; do not restart.
- MySQL audit queue depth / write latency — evidence loss risk.
- Disk usage on `gateway_logs`, `mysql_data`, `redis_data`.

Tune thresholds from measured baselines. The k6 scripts in `load-tests/` target
`host.docker.internal:8081`; run them against a staging stack, not production.

## 8. Security operations

- Never run `SPRING_PROFILES_ACTIVE=dev` in production. It disables client
  authentication and trusts `X-Tenant-Id`, and it exposes Swagger UI.
- Terminate TLS at a trusted proxy and forward only over a private network. Set
  `TRUST_PROXY=true` only when that proxy overwrites `X-Forwarded-For`.
- Set `OAUTH2_AUDIENCE` and `OAUTH2_REQUIRED_SCOPES` to gateway-specific values.
  An empty audience disables audience validation, and an empty scope list means
  any valid token from the issuer is accepted.
- Treat access tokens as bearer credentials: never log them, keep their
  lifetime short at the IdP, and use HTTPS end to end. The gateway caps token
  length but does not revoke tokens.
- Use `client_id` in `audit_logs` (and the CSV export) to attribute traffic.
  Rows created before the `client_id` migration are counted in
  `coverage.legacyRowsWithoutClientId`; do not present them as client-attributed
  evidence.
- Enable backend mTLS (`BACKEND_MTLS_ENABLED=true`) where the upstream supports
  it; keep key material in a Spring SSL bundle or secret mount.
- Keep MySQL and Redis ports bound to loopback or a private network. The Compose
  defaults do this; do not change `GATEWAY_BIND` to `0.0.0.0` without a firewall
  and TLS proxy.
- Use a dedicated MySQL user with only the privileges the gateway needs; the
  Compose user is for local development.
- Rotate database, Redis, operator and IdP credentials on a defined schedule.
- Review `audit_logs` for `decision = 'BLOCK'`, `attack_type` distribution,
  `AUTHENTICATION_FAILED`/`AUTH_DEPENDENCY_UNAVAILABLE` patterns and repeated
  401/429 operator events.
- Keep Actuator/Prometheus on the monitoring network. Do not expose
  `/actuator/env`, `/actuator/heapdump` or similar endpoints; only
  `health,info,metrics,prometheus` are exposed by configuration.
