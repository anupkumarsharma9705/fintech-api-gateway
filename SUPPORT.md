# Support

This repository is a proprietary product codebase, not a supported SaaS. The
[LICENSE](LICENSE) grants no usage rights and no warranty. Nothing in this file
creates an SLA; support is provided only under a separate written agreement.

## Supported versions

| Version | Status | Notes |
|---|---|---|
| `main` | Development | Builds and tests are expected to pass on every commit |
| `v1.x` | Design-partner baseline | Security fixes only, while the branch is maintained |
| Older tags | Unsupported | Upgrade before reporting an issue |

The project has not reached general availability. There is no long-term support
commitment, no 24×7 on-call, and no guaranteed response time outside a signed
agreement.

## Getting help

- **Bug or feature request:** open a GitHub issue with the commit SHA, deployment
  mode (`dev`/`prod`), expected behaviour and a minimal reproduction.
- **Security vulnerability:** follow [SECURITY.md](SECURITY.md). Do not open a
  public issue.
- **Commercial licensing, pilots, support contracts:** contact
  Anup Kumar Sharma at `anupkumarsharma9705@users.noreply.github.com`.

## What a commercial agreement can cover

The following are examples of scopes that can be contracted. They are not
included by default:

| Tier | Example scope | Example response target |
|---|---|---|
| Evaluation | Installation help, configuration review | Best effort, business days |
| Standard | 8×5 support, security patches, upgrade guidance | 1 business day |
| Premium | 24×7 incident bridge, named engineer, quarterly review | 1 hour for Sev-1 |
| Compliance add-on | Evidence export walkthrough, control-matrix mapping workshop | Scheduled |

Any target above is illustrative. The binding numbers belong in the signed
agreement, not in this file.

## Scope boundaries

Supported by the product: the Spring Boot gateway, its Docker image, the
Compose fixture, Kubernetes manifests in `deploy/k8s`, and the configuration
documented in [docs/CONFIGURATION.md](docs/CONFIGURATION.md).

Not supported: the demo frontend, the reference backend, the load-test host,
third-party IdP/MySQL/Redis/Kubernetes installations, and customer
modifications. Those are the operator's responsibility.

## Security and data handling

Do not send production secrets, access tokens, cardholder data or unredacted
audit exports in an issue or email. Use the private vulnerability channel for
security reports. Audit evidence exported from the gateway is sampled unless
`sampleRate` is `1.0`; read the `sampled` field before treating an export as a
census.

## Commercial position

This is a design-partner product. A pilot should start with a written scope, a
named compliance owner on the customer side, and an agreed evidence-retention
period. The [compliance matrix](docs/COMPLIANCE-MATRIX.md) lists what the
gateway can prove today and what remains an operator or assessor obligation.
