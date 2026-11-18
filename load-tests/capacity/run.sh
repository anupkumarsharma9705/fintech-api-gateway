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
# 0s measures a constant-arrival window, so the reported rate is the offered
# rate instead of a ramp-diluted average. Pass 20s to reproduce the
# pre-Phase-5 schedule.
RAMP="${5:-0s}"
P95_BUDGET="${P95_BUDGET:-50}"
GATEWAY_CPUS="${GATEWAY_CPUS:-2}"
# 0 = derive from the rate. Recorded with the result: a run that exhausts the
# VU pool measures the generator, not the gateway.
MAX_VUS="${MAX_VUS:-0}"
COOLDOWN="${COOLDOWN:-60}"
AUDIT_SAMPLE_RATE="${AUDIT_SAMPLE_RATE:-1.0}"
RATE_LIMIT_ALGORITHM="${RATE_LIMIT_ALGORITHM:-gcra}"
ABUSE_LIMIT_PER_IP="${RATE_ABUSE_LIMIT_PER_IP:-}"
TENANT="${CAPACITY_TENANT:-default}"
# dev supplies the fixture switch (auth off); capacity removes console log I/O,
# mirroring the prod logging shape. PROFILES=dev reproduces the pre-Phase-5
# logging cost. CAPACITY_PROFILES, not SPRING_PROFILES_ACTIVE: .env owns the
# latter for real deployments.
PROFILES="${CAPACITY_PROFILES:-dev,capacity}"
VIRTUAL_THREADS="${VIRTUAL_THREADS:-false}"

[[ "$AUDIT_SAMPLE_RATE" =~ ^(0(\.[0-9]+)?|1(\.0+)?)$ ]] \
  || { echo "AUDIT_SAMPLE_RATE must be within [0,1], got '$AUDIT_SAMPLE_RATE'"; exit 1; }
[[ "$RATE_LIMIT_ALGORITHM" =~ ^(gcra|sorted-set)$ ]] \
  || { echo "RATE_LIMIT_ALGORITHM must be gcra or sorted-set, got '$RATE_LIMIT_ALGORITHM'"; exit 1; }

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

if [[ -z "$ABUSE_LIMIT_PER_IP" ]]; then
  if [[ "$MODE" == "saturated" ]]; then ABUSE_LIMIT_PER_IP=100000000; else ABUSE_LIMIT_PER_IP=6000; fi
fi
export RATE_ABUSE_LIMIT_PER_IP="$ABUSE_LIMIT_PER_IP"
export AUDIT_SAMPLE_RATE RATE_LIMIT_ALGORITHM
export GATEWAY_CPUS
export SPRING_PROFILES_ACTIVE="$PROFILES"
export VIRTUAL_THREADS

echo "==> admission: algorithm=$RATE_LIMIT_ALGORITHM sample_rate=$AUDIT_SAMPLE_RATE abuse_per_ip=$ABUSE_LIMIT_PER_IP"
echo "==> logging: profiles=$PROFILES virtual_threads=$VIRTUAL_THREADS"

COMPOSE=(-f docker-compose.yml -f docker-compose.loadtest.yml)

reset_admission_state() {
  # A capacity scenario must start from a clean admission slate. The waf-block
  # fixture intentionally blacklists the caller after four blocks (24 h block
  # window, permanent blacklist); without this reset every later read from the
  # same IP is a 403 and measures the blacklist, not capacity. Scoped to one
  # tenant: never FLUSHALL, never touch another tenant.
  local tenant_tag pattern
  tenant_tag="$(printf '%s' "$TENANT" | sha256sum | cut -c1-16)"
  pattern="gw:v2:{t:${tenant_tag}}:*"
  echo "==> resetting admission state for tenant '$TENANT' ($pattern)"
  docker compose "${COMPOSE[@]}" exec -T redis sh -c \
    "redis-cli --scan --pattern '$pattern' | while read -r key; do redis-cli DEL \"\$key\" >/dev/null; done"
}

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

reset_admission_state

echo "==> warmup (10s, discarded)"
k6 run --quiet \
  -e "SCENARIO=$SCENARIO" -e "RATE=100" -e "RAMP=5s" -e "HOLD=10s" \
  -e "P95_BUDGET=10000" -e "WARMUP=true" \
  -o "json=load-tests/results/_warmup.json" \
  load-tests/capacity/capacity.js >/dev/null

# The waf-block warmup deliberately trips the blacklist; the measured run must
# not inherit it.
reset_admission_state

echo "==> measured run: $SCENARIO @ ${RATE} req/s for $HOLD"
JSON_OUT="load-tests/results/capacity-${TAG}.json"

# The post-run `docker stats` snapshot is taken after the cooldown, so it
# reports idle CPU. Peak CPU during the measured window is what the acceptance
# criterion talks about, so sample continuously while k6 runs.
RESOURCE_FILE="$(mktemp)"
(
  while true; do
    line="$(docker stats --no-stream --format '{{.Name}}|{{.CPUPerc}}|{{.MemUsage}}' 2>/dev/null \
      | grep 'fintech-gateway-gateway-1|' | head -1 || true)"
    if [[ -n "$line" ]]; then
      printf '%s|%s\n' "$(date -u +%FT%T)" "$line" >> "$RESOURCE_FILE"
    fi
    sleep 1
  done
) &
SAMPLER_PID=$!

set +e
k6 run \
  -e "SCENARIO=$SCENARIO" -e "RATE=$RATE" -e "RAMP=$RAMP" -e "HOLD=$HOLD" \
  -e "P95_BUDGET=$P95_BUDGET" -e "MAX_VUS=$MAX_VUS" \
  -o "json=$JSON_OUT" \
  load-tests/capacity/capacity.js
K6_EXIT=$?
set -e

# Stop sampling before the cooldown, otherwise idle time is averaged in.
kill "$SAMPLER_PID" 2>/dev/null || true
wait "$SAMPLER_PID" 2>/dev/null || true

RESOURCE_SAMPLES=0
CPU_PEAK=null
MEM_PEAK=null
if [[ -s "$RESOURCE_FILE" ]]; then
  RESOURCE_SAMPLES="$(wc -l < "$RESOURCE_FILE" | tr -d ' ')"
  CPU_PEAK="$(awk -F'|' '{c=$3; gsub(/%/,"",c); if (c+0>m) m=c+0} END { if (NR) printf "%.2f", m }' "$RESOURCE_FILE")"
  MEM_PEAK="$(awk -F'|' '{v=$4; sub(/^[ \t]+/,"",v); split(v,a," "); n=a[1]; u=n; gsub(/[0-9.]/,"",u); gsub(/[^0-9.]/,"",n); val=n+0; if (u=="GiB") val*=1024; else if (u=="KiB") val/=1024; if (val>m) m=val} END { if (NR) printf "%.1f", m }' "$RESOURCE_FILE")"
fi

# k6 exits 99 when a threshold is crossed. A saturated step is expected to
# cross the latency budget, and that artifact is still a valid result.
# Any other non-zero exit means the run itself failed and must not be
# published as a measurement.
if [[ "$K6_EXIT" -ne 0 && "$K6_EXIT" -ne 99 ]]; then
  echo "k6 measured run failed (exit $K6_EXIT)"
  exit 1
fi
if [[ ! -f load-tests/results/capacity-summary.json ]]; then
  echo "k6 produced no summary; refusing to record meta"
  exit 1
fi

# A status outside the scenario's allowlist means the run measured something
# other than the intended path (a blacklist, a WAF false positive, an upstream
# failure). The k6 thresholds do not fail on that, so the harness must.
UNEXPECTED="$(grep -o '"unexpected_status_rate"[[:space:]]*:[[:space:]]*[0-9.eE+-]*' \
  load-tests/results/capacity-summary.json | head -1 | sed 's/.*:[[:space:]]*//' || true)"
if [[ -z "$UNEXPECTED" ]]; then
  echo "could not read unexpected_status_rate from summary; refusing to record meta"
  exit 1
fi
if awk -v v="$UNEXPECTED" 'BEGIN { exit !(v > 0) }'; then
  echo "measured run produced unexpected statuses (rate $UNEXPECTED); refusing to record a contaminated artifact"
  exit 1
fi

json_number() {
  grep -o "\"$1\"[[:space:]]*:[[:space:]]*[0-9.eE+-]*" load-tests/results/capacity-summary.json \
    | head -1 | sed 's/.*:[[:space:]]*//' || true
}
json_literal() {
  grep -o "\"$1\"[[:space:]]*:[[:space:]]*\(true\|false\|[0-9.eE+-]*\)" load-tests/results/capacity-summary.json \
    | head -1 | sed 's/.*:[[:space:]]*//' || true
}

DROPPED="$(json_number dropped_iterations)"
EXECUTED_RATIO="$(json_number executed_ratio)"
SCHEDULED="$(json_number scheduled_iterations)"
GENERATOR_LIMITED="$(json_literal generator_limited)"
WINDOW="$(grep -o '"window"[[:space:]]*:[[:space:]]*"[a-z]*"' load-tests/results/capacity-summary.json \
  | head -1 | sed 's/.*:[[:space:]]*"//; s/"$//' || true)"

DROPPED="${DROPPED:-0}"
EXECUTED_RATIO="${EXECUTED_RATIO:-0}"
SCHEDULED="${SCHEDULED:-0}"
GENERATOR_LIMITED="${GENERATOR_LIMITED:-false}"
WINDOW="${WINDOW:-unknown}"

# A run that ran out of VUs did not offer the load it claims to have offered.
# It is still evidence that the gateway collapsed, but it is not a ceiling
# measurement, so the meta must carry the flag.
if [[ "$GENERATOR_LIMITED" == "true" ]]; then
  echo "WARNING: load generator exhausted its VU pool ($DROPPED dropped iterations); this run cannot support a ceiling claim"
fi

if [[ "$COOLDOWN" -gt 0 ]]; then
  echo "==> cooldown ${COOLDOWN}s so in-flight requests drain"
  sleep "$COOLDOWN"
fi

mv -f load-tests/results/capacity-summary.json \
   "load-tests/results/capacity-${TAG}.summary.json"

docker stats --no-stream --format '{{.Name}}|{{.CPUPerc}}|{{.MemUsage}}' \
  > "load-tests/results/capacity-${TAG}.container-stats.txt"

curl -fsS "http://localhost:8081/actuator/prometheus" \
  > "load-tests/results/capacity-${TAG}.prometheus.txt" 2>/dev/null || true

GIT_SHA="$(git rev-parse HEAD)"
GIT_DIRTY=false
[[ -n "$(git status --porcelain -- ':!load-tests/results')" ]] && GIT_DIRTY=true

cat > "load-tests/results/capacity-${TAG}.meta.json" <<EOF
{
  "tag": "${TAG}",
  "scenario": "${SCENARIO}",
  "mode": "${MODE}",
  "target_rate": ${RATE},
  "hold": "${HOLD}",
  "ramp": "${RAMP}",
  "p95_budget_ms": ${P95_BUDGET},
  "k6_exit_code": ${K6_EXIT},
  "gateway_cpus": ${GATEWAY_CPUS},
  "cooldown_s": ${COOLDOWN},
  "rate_limit_algorithm": "${RATE_LIMIT_ALGORITHM}",
  "audit_sample_rate": ${AUDIT_SAMPLE_RATE},
  "rate_abuse_limit_per_ip": ${ABUSE_LIMIT_PER_IP},
  "capacity_tenant": "${TENANT}",
  "admission_reset": true,
  "gateway_profiles": "${PROFILES}",
  "virtual_threads": "${VIRTUAL_THREADS}",
  "max_vus": ${MAX_VUS},
  "window": "${WINDOW}",
  "scheduled_iterations": ${SCHEDULED},
  "executed_ratio": ${EXECUTED_RATIO},
  "dropped_iterations": ${DROPPED},
  "generator_limited": ${GENERATOR_LIMITED},
  "git_sha": "${GIT_SHA}",
  "git_dirty": ${GIT_DIRTY},
  "host_os": "$(uname -srm)",
  "host_cpus": $(nproc),
  "kernel": "$(uname -r)",
  "docker_version": "$(docker version --format '{{.Server.Version}}' 2>/dev/null || echo unknown)",
  "k6_version": "$(k6 version 2>/dev/null || echo unknown)",
  "started_at": "${STAMP}",
  "resources": { "samples": ${RESOURCE_SAMPLES}, "cpu_peak_pct": ${CPU_PEAK}, "mem_peak_mib": ${MEM_PEAK} }
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
