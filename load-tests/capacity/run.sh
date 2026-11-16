#!/usr/bin/env bash
# Capacity test runner (Linux). Same contract as run.ps1.
#
#   ./load-tests/capacity/run.sh read saturated 1000 60s
#
# Linux is the publishing environment: Docker Desktop on Windows routes through
# a virtual switch and cannot produce portable numbers.

set -euo pipefail

SCENARIO="${1:-read}"
MODE="${2:-saturated}"
RATE="${3:-500}"
HOLD="${4:-120s}"
RAMP="${5:-20s}"
P95_BUDGET="${P95_BUDGET:-50}"
GATEWAY_CPUS="${GATEWAY_CPUS:-2}"
COOLDOWN="${COOLDOWN:-60}"

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$REPO_ROOT"

mkdir -p load-tests/results
STAMP="$(date -u +%Y%m%d-%H%M%S)"
TAG="${SCENARIO}-${MODE}-${RATE}rps-${STAMP}"

if [[ "$MODE" == "saturated" ]]; then
  export RATE_HIGH_TRUST=1000000 RATE_MEDIUM_TRUST=1000000 RATE_LOW_TRUST=1000000
else
  export RATE_HIGH_TRUST=50 RATE_MEDIUM_TRUST=30 RATE_LOW_TRUST=10
fi
export GATEWAY_CPUS

COMPOSE=(-f docker-compose.yml -f docker-compose.loadtest.yml)

echo "==> starting stack (gateway -> sink upstream)"
docker compose "${COMPOSE[@]}" up -d --build --wait

echo "==> waiting for readiness"
for _ in $(seq 1 60); do
  if curl -fsS -o /dev/null "http://localhost:8081/actuator/health/readiness"; then
    break
  fi
  sleep 2
done
curl -fsS -o /dev/null "http://localhost:8081/actuator/health/readiness" \
  || { echo "gateway not ready"; docker compose "${COMPOSE[@]}" logs --tail 100 gateway; exit 1; }

echo "==> warmup (10s, discarded)"
k6 run --quiet --no-summary \
  -e "SCENARIO=$SCENARIO" -e "RATE=100" -e "RAMP=5s" -e "HOLD=10s" \
  -e "P95_BUDGET=10000" \
  -o "json=load-tests/results/_warmup.json" \
  load-tests/capacity/capacity.js >/dev/null 2>&1 || true

echo "==> measured run: $SCENARIO @ ${RATE} req/s for $HOLD"
JSON_OUT="load-tests/results/capacity-${TAG}.json"
set +e
k6 run \
  -e "SCENARIO=$SCENARIO" -e "RATE=$RATE" -e "RAMP=$RAMP" -e "HOLD=$HOLD" \
  -e "P95_BUDGET=$P95_BUDGET" \
  -o "json=$JSON_OUT" \
  load-tests/capacity/capacity.js
K6_EXIT=$?
set -e

if [[ "$COOLDOWN" -gt 0 ]]; then
  echo "==> cooldown ${COOLDOWN}s so in-flight requests drain"
  sleep "$COOLDOWN"
fi

[[ -f load-tests/results/capacity-summary.json ]] \
  && mv -f load-tests/results/capacity-summary.json \
     "load-tests/results/capacity-${TAG}.summary.json"

docker stats --no-stream --format '{{.Name}}|{{.CPUPerc}}|{{.MemUsage}}' \
  > "load-tests/results/capacity-${TAG}.container-stats.txt"

curl -fsS "http://localhost:8081/actuator/prometheus" \
  > "load-tests/results/capacity-${TAG}.prometheus.txt" 2>/dev/null || true

GIT_SHA="$(git rev-parse HEAD)"
GIT_DIRTY=false
[[ -n "$(git status --porcelain)" ]] && GIT_DIRTY=true

cat > "load-tests/results/capacity-${TAG}.meta.json" <<EOF
{
  "tag": "${TAG}",
  "scenario": "${SCENARIO}",
  "mode": "${MODE}",
  "target_rate": ${RATE},
  "hold": "${HOLD}",
  "ramp": "${RAMP}",
  "p95_budget_ms": ${P95_BUDGET},
  "gateway_cpus": ${GATEWAY_CPUS},
  "cooldown_s": ${COOLDOWN},
  "git_sha": "${GIT_SHA}",
  "git_dirty": ${GIT_DIRTY},
  "host_os": "$(uname -srm)",
  "host_cpus": $(nproc),
  "kernel": "$(uname -r)",
  "docker_version": "$(docker version --format '{{.Server.Version}}' 2>/dev/null || echo unknown)",
  "k6_version": "$(k6 version 2>/dev/null || echo unknown)",
  "started_at": "${STAMP}"
}
EOF

echo
echo "Artifacts:"
echo "  ${JSON_OUT}"
echo "  load-tests/results/capacity-${TAG}.summary.json"
echo "  load-tests/results/capacity-${TAG}.meta.json"
echo "  load-tests/results/capacity-${TAG}.prometheus.txt"
echo
echo "Publish only with the meta.json - a rate without hardware is not a result."

exit "$K6_EXIT"
