# Kubernetes deployment

The gateway ships as a Kustomize base plus two overlays:

| Path | Purpose |
|---|---|
| `deploy/k8s/base/` | Production-shaped Deployment, Service, PDB, HPA, NetworkPolicy, probes |
| `deploy/k8s/overlays/prod/` | 3 replicas, immutable image tag, TLS Ingress |
| `deploy/k8s/overlays/dev/` | 1 replica, in-cluster MySQL/Redis, no NetworkPolicy |

Both overlays render without a cluster:

```bash
kubectl kustomize deploy/k8s/overlays/prod > /tmp/prod.yaml
kubectl kustomize deploy/k8s/overlays/dev  > /tmp/dev.yaml
```

Last verified render: `dev` 12 objects, `prod` 9 objects, exit 0 (kubectl
v1.34 client-side kustomize). **No live cluster was available in this
repository's environment**, so the manifests are render-validated, not
cluster-tested. Treat the first real deployment as an integration test.

## What the manifests enforce

- **No shared mutable image tag in production.** The prod overlay pins
  `ghcr.io/anupkumarsharma/fintech-gateway:1.0.0`; change it deliberately.
- **RollingUpdate with `maxUnavailable: 0`** and a 10 s `preStop` sleep, so the
  endpoint controller removes a pod before the JVM stops accepting traffic.
  `terminationGracePeriodSeconds: 45` leaves time for the audit writer to flush.
- **Three probes.** Startup allows 150 s for JVM + Flyway; readiness includes
  `db` and `redis` (see `management.endpoint.health.group.readiness`), so a pod
  with an unreadable limiter is removed from the Service instead of failing
  closed for live traffic. Liveness checks only the process.
- **PodDisruptionBudget `minAvailable: 2`** on a 3-replica minimum.
- **HPA 3 → 12 replicas** on 70 % CPU / 80 % memory. Admission state lives in
  Redis, so a new replica is useful immediately; scale-down is deliberately
  slow (1 pod / 120 s) to avoid flapping.
- **Default-deny NetworkPolicy.** Ingress only from `ingress-nginx`; egress only
  to DNS, `data` (MySQL/Redis) and the upstream namespace. If MySQL/Redis are
  managed services, replace the `namespaceSelector` blocks with `ipBlock` CIDRs.
- **Restricted pod security**: non-root UID 1001, read-only root filesystem,
  `allowPrivilegeEscalation: false`, all capabilities dropped,
  `seccompProfile: RuntimeDefault`, no service-account token mounted.
- **Writable paths are explicit emptyDirs**: `/tmp` (256 MiB) and
  `/var/log/fintech` (2 GiB). The `capacity` logging profile writes only files,
  so a full log volume cannot block the request path.

## Before the first deploy

1. **Create the Secret.** Copy `base/secret.example.yaml`, replace every value,
   and apply it through your secret manager. `MYSQL_USER`, `MYSQL_PASSWORD`,
   `REDIS_PASSWORD`, `INTERNAL_OPS_API_KEY` and the OAuth2 settings are
   required. The gateway fails closed when the IdP configuration is missing.
2. **Point at real data stores.** `base/configmap.yaml` currently names
   `mysql.data.svc.cluster.local` and `redis-cluster.data.svc.cluster.local`.
   Either create those Services or override `MYSQL_HOST` / `REDIS_CLUSTER_NODES`
   in the overlay.
3. **Terminate TLS at the Ingress** and install the certificate Secret named
   `fintech-gateway-tls`. The gateway itself speaks HTTP inside the cluster and
   sets HSTS; do not expose the container port outside the cluster.
4. **Build and push the image** (the release workflow does this on a `v*` tag):

   ```bash
   docker build -f fintech-gateway/fintech-gateway/Dockerfile \
     -t ghcr.io/anupkumarsharma/fintech-gateway:1.0.0 .
   docker push ghcr.io/anupkumarsharma/fintech-gateway:1.0.0
   ```

5. **Apply:**

   ```bash
   kubectl apply -f deploy/k8s/base/secret.example.yaml   # after replacing values
   kubectl apply -k deploy/k8s/overlays/prod
   kubectl -n fintech-gateway rollout status deploy/fintech-gateway
   ```

## Redis Cluster

Redis Cluster support is a Spring profile, not a second code path:

```properties
SPRING_PROFILES_ACTIVE=prod,redis-cluster
REDIS_CLUSTER_NODES=redis-0.redis:6379,redis-1.redis:6379,redis-2.redis:6379
```

`application-redis-cluster.properties` enables topology refresh every 30 s with
adaptive redirects. The key layout is already cluster-safe: every multi-key Lua
script operates on one tenant's keys, and all of them share the
`{t:<16-hex>}` hash tag (`RedisKeysTest` asserts this). Do not rename keys or
remove the tag without re-running that test.

Requirements for the cluster:

- `cluster-enabled yes` and at least 3 masters;
- a password if the cluster is reachable from outside the pod network;
- TLS if your provider requires it (`spring.data.redis.ssl.enabled=true`);
- enough `maxmemory` that admission keys are never evicted. Limiter state is
  reconstructible but eviction turns a quota into a reset, which is a
  compliance-relevant event.

MySQL is not modified by the manifests. Use a managed MySQL 8 with
`REQUIRE SSL` for the gateway user; `MYSQL_SSL=true` is set in the base
ConfigMap.

## Multi-replica invariants

The gateway was designed to scale horizontally without sticky sessions:

| State | Where it lives | Consequence across replicas |
|---|---|---|
| Admission quota / abuse ceiling | Redis, tenant hash tag | A request to any pod decrements the same bucket |
| Idempotency claim | Redis, owner-tokened Lua compare-and-set | A retry to a different pod replays the same response |
| Trust tier / blacklist | Redis | Penalties apply to every pod |
| Audit evidence | MySQL via each pod's buffer | Complete in MySQL; per-pod counters must be summed in Prometheus |
| Compliance exports | MySQL query | A single pod returns all replicas' rows |

Prometheus counters are per pod and reset when a pod restarts. Use
`sum by (tenant_id) (gateway_requests_total)` and never `avg` for counters;
the capacity harness records `container_id` for the same reason.

## Scaling evidence status

- Manifests: render-validated (above).
- Redis Cluster: key layout and profile implemented; **not exercised against a
  live cluster** in this environment.
- Multi-replica throughput: **not measured**. The capacity rig is a single
  replica on a 12 vCPU Windows host; a linearity claim needs a second host or a
  real cluster.
- One-hour soak and dependency chaos: see [CHAOS.md](CHAOS.md) and
  [../load-tests/CAPACITY.md](../load-tests/CAPACITY.md) for what was actually
  run on this host.

## Rollback

```bash
kubectl -n fintech-gateway rollout undo deploy/fintech-gateway
kubectl -n fintech-gateway rollout status deploy/fintech-gateway
```

Schema changes are Flyway migrations. They are forward-only: a rollback of the
image must still run against a schema it understands. Keep the previous image
compatible with one migration ahead, or restore the database snapshot described
in [OPERATIONS.md](OPERATIONS.md).
