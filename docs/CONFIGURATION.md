# Configuration Reference

All runtime configuration is externalized through environment variables. The
defaults below come from `application.properties` and the `dev`/`prod` profile
files; the Docker Compose stack supplies the container-network values.

> Secrets (`MYSQL_PASSWORD`, `INTERNAL_OPS_API_KEY`, `REDIS_PASSWORD`, TLS keys)
> must come from a secret manager or the deployment environment. Never commit a
> populated `.env`.

---

## Core

| Variable | Default | Description |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | none | `dev` enables Swagger, dev CORS/key defaults and disables client auth; `prod` disables docs, requires explicit CORS and refuses to start with client auth disabled |
| `SERVER_PORT` | `8081` | Gateway HTTP port |
| `TOMCAT_MAX_THREADS` | `200` | Maximum Tomcat worker threads |
| `LOG_DIR` | `logs` | Directory for `audit.log` and `gateway.log` |
| `TZ` | host default | JVM/container timezone; containers set `UTC` |

Tomcat also uses fixed limits from `application.properties`: 20 minimum spare
threads, accept count 100, max connections 8192, 2 MB swallow/form-post limit.

## MySQL

| Variable | Default | Description |
|---|---|---|
| `MYSQL_HOST` | `localhost` | MySQL host |
| `MYSQL_PORT` | `3307` | MySQL port (Compose publishes 3307 on the host) |
| `MYSQL_DB` | `FintechGateway` | Database name |
| `MYSQL_USER` | `fintech` | Database user |
| `MYSQL_PASSWORD` | **required** | Database password; startup fails when unset |
| `MYSQL_SSL` | `false` | Enable JDBC TLS |
| `MYSQL_ALLOW_PUBLIC_KEY_RETRIEVAL` | `true` | Allow RSA key retrieval for non-TLS connections; set `false` with TLS |
| `DB_POOL_MAX` | `20` | Hikari maximum pool size |
| `DB_POOL_MIN` | `5` | Hikari minimum idle connections |

The pool has a 3 s connection timeout, 1 s validation timeout, 30 min max
lifetime and 60 s keepalive. Flyway runs at startup with
`baseline-on-migrate=true` and `validate-on-migrate=true`.

## Redis

| Variable | Default | Description |
|---|---|---|
| `REDIS_HOST` | `localhost` | Redis host |
| `REDIS_PORT` | `6379` | Redis port |
| `REDIS_PASSWORD` | empty | Redis AUTH password; leave empty only for isolated development |

Lettuce timeouts are 2 s connect/command. The pool is 16 max active, 8 max idle,
2 min idle. Redis holds rate-limit windows, idempotency records and trust state;
it is not a cache that may silently lose data.

## Upstream backend

| Variable | Default | Description |
|---|---|---|
| `BACKEND_URL` | `http://localhost:8080` | Upstream base URL |
| `BACKEND_CONNECT_TIMEOUT` | `2s` | TCP connect timeout |
| `BACKEND_READ_TIMEOUT` | `10s` | Response read timeout |
| `BACKEND_MTLS_ENABLED` | `false` | Enable mutual TLS to the backend |
| `BACKEND_MTLS_BUNDLE` | `gateway-backend` | Spring SSL bundle name |
| `MAX_UPSTREAM_RESPONSE_BYTES` | `10485760` | Maximum buffered upstream response; larger responses return 502 |

### mTLS bundle example

```properties
gateway.backend.mtls.enabled=true
gateway.backend.mtls.bundle=gateway-backend

spring.ssl.bundle.pem.gateway-backend.truststore.certificate=file:/etc/gateway/backend-ca.pem
spring.ssl.bundle.pem.gateway-backend.keystore.certificate=file:/etc/gateway/client-cert.pem
spring.ssl.bundle.pem.gateway-backend.keystore.private-key=file:/etc/gateway/client-key.pem
```

The private key can alternatively be supplied through
`spring.ssl.bundle.pem.gateway-backend.keystore.private-key-password` or a
mounted keystore. The gateway never reads key material from its own
`gateway.*` namespace.

## CORS

| Variable | Default | Description |
|---|---|---|
| `CORS_ALLOWED_ORIGINS` | `http://localhost:3000,http://localhost:8081` (dev adds `:5500`) | Comma-separated exact origins; empty disables CORS in prod |

`allowCredentials` is always `false`. Wildcards are not used. Methods default to
`GET`, `POST`, `PUT`, `PATCH`, `DELETE` and `OPTIONS`. Allowed headers include
`Authorization`, `Content-Type`, `Accept`, `Idempotency-Key`, `X-Tenant-Id`,
`X-Request-Id` and `X-Internal-Ops-Key`. Exposed response headers include
`X-Request-Id`, `X-RateLimit-Limit`, `X-RateLimit-Remaining`,
`X-RateLimit-Reset` and `Retry-After`.

## Risk scoring

| Variable | Default | Description |
|---|---|---|
| `RISK_BLOCK_THRESHOLD` | `71` | Score at or above this value is BLOCK |
| `RISK_THROTTLE_THRESHOLD` | `31` | Score at or above this value is THROTTLE |
| `RISK_ZONE` | `Asia/Kolkata` | Business timezone used for the off-hours score |

Scoring is deterministic: endpoint sensitivity (payments 30/15, balance 10,
profile 5, other API 15/5), payload size (10 above 10 KB, 20 above 100 KB),
off-hours 23:00–05:59 adds 15, and trust adjustment is HIGH −10, MEDIUM 0,
LOW +20, BLACKLISTED +100. The result is clamped to 0–100.

## Rate limiting

| Variable | Default | Description |
|---|---|---|
| `RATE_LIMIT_ENABLED` | `true` | Disable only in isolated tests; production must stay enabled |
| `RATE_LIMIT_WINDOW` | `1m` | Sliding-window length |
| `RATE_HIGH_TRUST` | `50` | Requests per window for HIGH trust |
| `RATE_MEDIUM_TRUST` | `30` | Requests per window for MEDIUM trust |
| `RATE_LOW_TRUST` | `10` | Requests per window for LOW trust |
| `RATE_THROTTLE_COST` | `3` | Extra slots consumed by a THROTTLE decision |

Blacklisted callers have a limit of 0 and are rejected before the limiter runs.
Keys are `ratelimit:<tenant>:<ip>`. The Lua script removes expired members and
adds `cost` members atomically, so the window is exact across instances.

## Idempotency

| Variable | Default | Description |
|---|---|---|
| `IDEMPOTENCY_TTL` | `24h` | How long a completed response is replayable |
| `IDEMPOTENCY_REQUIRED_PATHS` | `/api/payments` | Comma-separated path prefixes requiring a key on unsafe methods |
| `IDEMPOTENCY_MAX_KEY_LENGTH` | `255` | Maximum accepted key length |

Keys must match `^[A-Za-z0-9._:-]+$`. The Redis key is
`idem:<tenant>:<key>`. A claim stores `IN_PROGRESS:<owner-token>`; completion
and release only succeed for that token, so a claim that outlived its TTL
cannot overwrite or delete a newer caller's claim. A completed record stores
the status code, content type, content encoding and raw body bytes (Base64
inside the JSON envelope), so a replayed gzip or binary response is
byte-identical. Only 2xx responses are stored, and non-2xx responses release
the claim.

## Security

| Variable | Default | Description |
|---|---|---|
| `TRUST_PROXY` | `false` | Trust the first `X-Forwarded-For` address; enable only behind a proxy that overwrites it |
| `MAX_BODY_BYTES` | `1048576` | Request body cap; larger bodies return 413 |
| `INTERNAL_OPS_API_KEY` | empty | Key for `/feedback/**` and `/compliance/**`; empty rejects every call |
| `OPS_MAX_FAILED_ATTEMPTS` | `10` | Failed operator attempts before lockout |
| `OPS_LOCKOUT_WINDOW` | `15m` | Lockout counter TTL |
| `HSTS_ENABLED` | `true` | Emit `Strict-Transport-Security`; disable only if TLS is terminated elsewhere without HSTS |

The body limit is additionally floored at 8192 bytes inside the payload
normalizer, so a misconfigured value cannot disable the WAF's minimum buffer.
Compressed request bodies are rejected with 415: the WAF inspects plaintext and
there is no bounded decompression stage.

The operator key is hashed with SHA-256 and compared with
`MessageDigest.isEqual`. Failed attempts are counted in Redis per caller IP; if
Redis is unavailable the operator endpoints fail closed with 503. There is no
hot reload: rotate the key and roll the gateway.

## Client authentication (OAuth2)

Authentication is enabled by default. The gateway is an OAuth2 resource server:
it validates client-credentials JWTs that an authorization server issues. It
does not issue tokens, run a login flow, or enable Spring Security's servlet
filter chain.

| Variable | Default | Description |
|---|---|---|
| `GATEWAY_AUTH_ENABLED` | `true` (`false` in `dev`) | Enable JWT authentication; the `prod` profile refuses to start when this is `false` |
| `OAUTH2_ISSUER_URI` | empty | Issuer URI; validates `iss` and enables metadata discovery when no JWK set URI is set |
| `OAUTH2_JWK_SET_URI` | empty | Direct JWK set URI; preferred in production because startup does not call IdP metadata |
| `OAUTH2_AUDIENCE` | `fintech-gateway` | Required `aud` value; set empty to disable audience validation |
| `OAUTH2_TENANT_CLAIM` | `tenant_id` | Claim copied into the authenticated tenant |
| `OAUTH2_CLIENT_ID_CLAIM` | `client_id` | Claim used as the audit client id; falls back to `sub` |
| `OAUTH2_SCOPE_CLAIM` | `scope` | Claim holding a space-delimited string or a JSON array of scopes |
| `OAUTH2_REQUIRED_SCOPES` | empty (`gateway.api` in `prod`) | Comma-separated scopes that must all be present |
| `OAUTH2_CLOCK_SKEW` | `60s` | Allowed clock skew for `exp`/`nbf`/`iat` |

Validation behavior:

- When auth is enabled, startup fails unless `OAUTH2_ISSUER_URI` or
  `OAUTH2_JWK_SET_URI` is configured. A missing decoder is never treated as
  "allow".
- If only the issuer URI is set, startup performs metadata discovery and
  therefore depends on the IdP being reachable. Use the JWK set URI to avoid
  that dependency.
- Invalid signature, expiry, issuer or audience returns 401 `invalid_token`.
  Missing/invalid tenant claims return 403 `invalid_token_claims`. Missing
  scopes return 403 `insufficient_scope`. JWKS/network failures return 503
  `auth_dependency_unavailable`.
- Failure responses carry a `WWW-Authenticate: Bearer` challenge when RFC 6750
  defines one; `insufficient_scope` includes the required scopes.
- The token must be a Bearer token and is capped at 8,192 characters.
- Every failure is audited as `BLOCK` and counted in
  `gateway.auth.failures{reason}`.

The `dev` profile sets `GATEWAY_AUTH_ENABLED=false` and trusts `X-Tenant-Id` so
the demo runs without an IdP. This is the only profile that does so; the `prod`
profile refuses to start if authentication is disabled.

### Compose note

`docker-compose.yml` lists the auth variables as commented examples. Compose
only injects variables that appear under `environment:`, so uncomment the block
(or add your own override file) before running with `SPRING_PROFILES_ACTIVE=prod`.
Do not pass empty strings for `GATEWAY_AUTH_ENABLED` or
`OAUTH2_REQUIRED_SCOPES`: they override the profile defaults and a boolean or
list cannot bind an empty value reliably.

## Tenancy

| Variable | Default | Description |
|---|---|---|
| `TENANT_HEADER` | `X-Tenant-Id` | Legacy tenant hint header |
| `DEFAULT_TENANT` | `default` | Tenant used when auth is disabled and the header is absent |
| `TENANT_PATTERN` | `^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$` | Accepted tenant format |

When authentication is enabled, the tenant comes from the signed
`OAUTH2_TENANT_CLAIM`. The `X-Tenant-Id` header is optional; if present it must
equal the authenticated tenant or the request is rejected with 403
`tenant_mismatch`. If the token tenant does not match `TENANT_PATTERN`, the
request is rejected with 403 `invalid_token_claims`.

The tenant id scopes rate limits, idempotency keys and audit rows. It is an
identity attribute only because it comes from the validated token; the header
is never an identity when auth is on.

## Compliance evidence

| Variable | Default | Description |
|---|---|---|
| `COMPLIANCE_DEFAULT_WINDOW` | `24h` | Window used when `from`/`to` are omitted |
| `COMPLIANCE_MAX_WINDOW` | `90d` | Maximum accepted window; must be at least the default |
| `COMPLIANCE_DEFAULT_ROWS` | `1000` | Default CSV rows |
| `COMPLIANCE_MAX_ROWS` | `10000` | Hard CSV row cap; larger requests are truncated and flagged |

Both endpoints are under `/compliance/evidence` and require
`X-Internal-Ops-Key`:

- `GET /compliance/evidence/summary?from=<ISO-8601>&to=<ISO-8601>`
- `GET /compliance/evidence/audit.csv?from=<ISO-8601>&to=<ISO-8601>&limit=<n>`

Windows must have `from < to`. A `to` more than five minutes in the future is
rejected with 400 `future_time_range`; an over-long window returns 400
`range_too_large`; a non-positive `limit` returns 400 `invalid_limit`. Both
responses use `Cache-Control: no-store`.

The CSV response also sets `X-Evidence-Rows` and `X-Evidence-Truncated`. Every
export writes a `COMPLIANCE_EXPORT` audit event and increments
`gateway.compliance.export{type}`.

## Docker Compose variables

| Variable | Default | Description |
|---|---|---|
| `MYSQL_ROOT_PASSWORD` | **required** | MySQL root password for the container |
| `MYSQL_PUBLISHED_PORT` | `3307` | Host port for MySQL, bound to 127.0.0.1 |
| `REDIS_PUBLISHED_PORT` | `6379` | Host port for Redis, bound to 127.0.0.1 |
| `GATEWAY_BIND` | `127.0.0.1` | Host interface for the gateway port; set `0.0.0.0` only behind a firewall/TLS proxy |
| `GATEWAY_PORT` | `8081` | Host port for the gateway |

## Actuator

Exposure is configured in `application.properties`:

```
management.endpoints.web.exposure.include=health,info,metrics,prometheus
management.endpoint.health.show-details=never
```

Standard Spring Boot relaxed binding can override these, for example
`MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE=health,prometheus`. Keep `prometheus`
reachable only from the monitoring network; do not publish the management port
to the internet.
