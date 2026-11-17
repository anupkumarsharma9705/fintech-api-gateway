# Fintech Gateway Demo UI

A single-file static demo for the Adaptive Fintech API Security Gateway. It
exists to make the gateway's request pipeline visible: proxy, WAF, idempotency,
adaptive rate limiting, trust tiers and operator recovery.

> This is a demo page, not a production console. It has no login, no build step
> and no dependency on a JavaScript framework. Do not ship it as-is to
> customers.

---

## Run it

Start the backend and gateway first (see the repository root `README.md`), then
serve the file over HTTP:

```bash
cd fintech-frontend/fintech-frontend
python3 -m http.server 3000
```

Open <http://localhost:3000>. The gateway must allow the origin
`http://localhost:3000`; the `dev` profile does by default.

Opening `index.html` directly with `file://` usually fails because of browser
CORS rules. Use a local HTTP server.

---

## Configuration

The gateway URL is a single constant near the top of the script block:

```javascript
const GATEWAY = 'http://localhost:8081';
```

Change it when the gateway runs elsewhere. The operator key is entered in the
Feedback section and sent as `X-Internal-Ops-Key`. The pre-filled value
`dev-ops-key-change-me` only works with the `dev` profile; production rejects
it.

---

## What each section demonstrates

### 1. Health

`GET /health` is a liveness probe. It returns:

```json
{ "status": "UP", "service": "fintech-gateway" }
```

It deliberately does not call MySQL, Redis or the backend. Dependency health is
available at `GET /actuator/health/readiness` (MySQL and Redis) and
`GET /actuator/health` (all contributors). A Redis outage makes readiness DOWN
and makes admission fail closed with HTTP 503, but it does not make the process
unhealthy.

### 2. Normal request (ALLOW)

`GET /api/account/balance?accountId=ACC123` passes through the filter pipeline,
gets a low risk score and is proxied to the backend.

### 3. Payment and idempotency

`POST /api/payments/transfer` requires an `Idempotency-Key` header. The first
request is executed and its response is stored in Redis. A retry with the same
key returns the stored response with `Idempotency-Replayed: true` and never
reaches the backend. A concurrent retry while the first request is still
running gets HTTP 409 with `Retry-After: 1`.

Keys are scoped to the resolved tenant. With authentication enabled the tenant
comes from the `tenant_id` token claim, so changing the `X-Tenant-Id` header
does not move the request to another tenant; a mismatch is rejected. In the
`dev` profile, where authentication is disabled, the header is used directly
and defaults to `default`.

### 4. SQL injection

The WAF normalizes the body (percent-decoding, HTML entities, Unicode
compatibility forms) before matching signatures. A detected attack returns
HTTP 403 with a generic `request_rejected` body; the threat type is recorded in
the audit trail and the caller's trust tier is downgraded. The response does not
echo the matched signature.

### 5. Adaptive rate limiting

Each request consumes a slot from a tenant + authenticated-client GCRA bucket,
with a separate per-IP abuse ceiling. GCRA admits a burst of the tier limit and
then refills steadily; set `RATE_LIMIT_ALGORITHM=sorted-set` when an exact
per-window bound is required. Limits depend on trust tier:

| Tier | Requests per minute |
|---|---:|
| HIGH | 50 |
| MEDIUM | 30 |
| LOW | 10 |
| BLACKLISTED | 0 |

When the risk engine returns THROTTLE, the request consumes the configured
extra cost (default 3 slots) and is marked `X-Gateway-Throttled: true`. If the
budget is exhausted the gateway returns HTTP 429 with `Retry-After`,
`X-RateLimit-Limit`, `X-RateLimit-Remaining` and `X-RateLimit-Reset`. There is
no artificial sleep in the gateway.

Repeated throttles downgrade the caller to LOW trust; repeated blocks lead to a
blacklist. A successful request in good standing is recorded, and sustained
clean traffic upgrades MEDIUM to HIGH.

### 6. False-positive recovery

`POST /feedback/false-positive` is an operator endpoint. It requires
`X-Internal-Ops-Key`; the key is compared in constant time and failed attempts
are rate-limited per IP in Redis. A successful reset clears the blacklist,
block count, throttle count, good-behaviour count and trust key, then restores
MEDIUM trust and writes a `trust_history` row.

Example:

```bash
curl -X POST http://localhost:8081/feedback/false-positive \
  -H 'Content-Type: application/json' \
  -H "X-Internal-Ops-Key: $INTERNAL_OPS_API_KEY" \
  -d '{"ip":"203.0.113.5","reason":"support ticket 1234"}'
```

---

## Security notes

- CORS is an explicit origin allowlist (`gateway.cors.allowed-origins`), not a
  wildcard. Credentials are disabled.
- The operator key is never stored in the page; it lives in the input field
  and is sent only to `/feedback/**`. Do not put a real key in a public page.
  The same key gates the `/compliance/**` evidence endpoints used from operator
  tooling, not from this page.
- This demo does not attach an OAuth2 `Authorization` header. It works only
  with the `dev` profile, where client authentication is explicitly disabled.
  To exercise a production-style setup, use `curl` or an API client with a
  Bearer token; see the root `README.md`.
- `X-Tenant-Id` is a development-only hint. Production derives the tenant from
  the validated token claim; a mismatched header is rejected with 403.
- All data in the demo is simulated (`ACC123`, `PAY-DEMO-001`). No real account
  numbers or personal data are used.
