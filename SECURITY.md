# Security Policy

## Supported versions

This repository has no generally available release yet. Security fixes are
applied to the `main`/`master` branch and to the latest design-partner build.
Older snapshots are not supported.

## Reporting a vulnerability

Use GitHub's private vulnerability reporting: open the repository's **Security**
tab and choose **Report a vulnerability**. Do not open a public issue for a
suspected vulnerability, and do not include production secrets, access tokens,
customer data or unredacted audit exports in a report.

Please include:

- the affected commit and deployment mode (`dev` or `prod`, auth enabled or
  disabled);
- the endpoint and request shape needed to reproduce the issue;
- the expected and observed behavior, with impact;
- relevant logs with secrets and personal data redacted;
- whether the issue requires control of the IdP, MySQL, Redis, the upstream
  backend or the host.

No response SLA is offered. Reports are reviewed on a best-effort basis, and
validated issues are fixed before public disclosure. Please allow time for a fix
before publishing details.

## Scope

In scope:

- OAuth2/JWT validation, tenant binding and scope enforcement;
- tenant-scoped rate limiting and idempotency;
- WAF normalization and request/response bounds;
- operator authentication and the compliance evidence endpoints;
- secret handling and container/runtime configuration in this repository.

Out of scope:

- the demo frontend and the reference backend, which are not production
  services;
- the `dev` profile, which intentionally disables client authentication and
  trusts `X-Tenant-Id`;
- vulnerabilities that require a compromised authorization server, database,
  Redis instance or host;
- dependency advisories without a demonstrated exploit path against this code
  (still worth reporting, but may be handled as maintenance).

## Deployment expectations

The gateway is secure only when deployed as documented:

- run with the `prod` profile, never `dev`;
- configure `OAUTH2_ISSUER_URI` or `OAUTH2_JWK_SET_URI`, an audience and the
  required scopes;
- terminate TLS in front of the gateway and keep MySQL, Redis and Actuator on
  private networks;
- source secrets from a secret manager, not a committed or shared `.env`;
- rotate the operator key and IdP signing keys as described in
  [docs/OPERATIONS.md](docs/OPERATIONS.md);
- keep the gateway, MySQL, Redis and the JVM patched.

See [docs/CONFIGURATION.md](docs/CONFIGURATION.md) and
[docs/OPERATIONS.md](docs/OPERATIONS.md) for the full configuration and
operating rules.
