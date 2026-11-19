/**
 * Chaos probe.
 *
 * Unlike capacity.js this does not assert latency: it classifies outcomes so a
 * controller can prove the failure contract.
 *
 *   - Redis unavailable  -> admission fails CLOSED (503), never allows traffic
 *   - MySQL unavailable  -> reads still succeed; audit loss is bounded and counted
 *   - gateway killed     -> connections are refused until the orchestrator restarts it
 *
 * Run through load-tests/chaos/chaos.ps1, which owns the fault windows.
 */

import http from 'k6/http';
import { Counter } from 'k6/metrics';

const BASE = __ENV.BASE_URL || 'http://localhost:8081';
const RATE = Number(__ENV.RATE || 50);
const DURATION = __ENV.DURATION || '10s';
const PHASE = __ENV.PHASE || 'unknown';
const TAG = __ENV.TAG || 'local';
const PATH = __ENV.PATH || '/api/account/balance?accountId=ACC123';

const ok = new Counter('chaos_ok');
const unavailable = new Counter('chaos_unavailable');
const serverError = new Counter('chaos_server_error');
const blocked = new Counter('chaos_blocked');
const unreachable = new Counter('chaos_unreachable');
const other = new Counter('chaos_other');

export const options = {
  discardResponseBodies: true,
  summaryTrendStats: ['count', 'avg', 'med', 'p(95)', 'max'],
  scenarios: {
    probe: {
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: Math.max(20, RATE),
      maxVUs: Math.max(100, RATE * 4),
      gracefulStop: '5s',
    },
  },
  // This is a correctness probe, not a capacity measurement. A 503 during a
  // fault window is the expected result, so there is no threshold to cross.
  thresholds: {},
};

export default function () {
  const res = http.get(`${BASE}${PATH}`, { timeout: '5s' });
  if (res.status === 0) {
    unreachable.add(1);
  } else if (res.status === 200) {
    ok.add(1);
  } else if (res.status === 503) {
    unavailable.add(1);
  } else if (res.status === 429 || res.status === 403) {
    blocked.add(1);
  } else if (res.status >= 500) {
    serverError.add(1);
  } else {
    other.add(1);
  }
}

function count(data, name) {
  const metric = data.metrics[name];
  return metric && metric.values ? (metric.values.count || 0) : 0;
}

export function handleSummary(data) {
  const duration = data.metrics.http_req_duration;
  const out = {
    tag: TAG,
    phase: PHASE,
    rate: RATE,
    duration: DURATION,
    requests: count(data, 'iterations'),
    ok: count(data, 'chaos_ok'),
    unavailable: count(data, 'chaos_unavailable'),
    server_error: count(data, 'chaos_server_error'),
    blocked: count(data, 'chaos_blocked'),
    unreachable: count(data, 'chaos_unreachable'),
    other: count(data, 'chaos_other'),
    latency_p95_ms: duration && duration.values ? duration.values['p(95)'] : null,
    latency_max_ms: duration && duration.values ? duration.values.max : null,
  };
  return {
    [`load-tests/results/chaos-${TAG}-${PHASE}.summary.json`]: JSON.stringify(out, null, 2),
    stdout: `${JSON.stringify(out)}\n`,
  };
}
