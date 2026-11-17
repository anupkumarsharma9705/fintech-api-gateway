/**
 * Open-loop capacity test for the Fintech API Gateway.
 *
 * The existing load-tests/*.js scripts are CLOSED-loop: each VU sleeps between
 * requests, so the achieved rate is the script's schedule, not the server's
 * capacity. This script uses an arrival-rate executor, so the gateway is driven
 * at a fixed request rate regardless of how slow it becomes. That is the only
 * way to find the knee.
 *
 * Usage:
 *   k6 run -e SCENARIO=read -e RATE=1000 -e HOLD=60s load-tests/capacity/capacity.js
 *
 * Scenarios:
 *   health     GET  /health                        (bypasses the admission pipeline)
 *   read       GET  /api/account/balance           (full admission pipeline)
 *   write      POST /api/payments/transfer         (idempotency acquire + complete)
 *   waf-clean  POST 4 KB benign body               (WAF cost on clean traffic)
 *   waf-block  GET  with SQLi in query             (WAF must block 100%)
 *   mixed      85% read / 10% write / 5% attack
 *
 * This measures throughput, latency and failure-under-load. It does NOT assert
 * business correctness - that belongs to the functional smoke test.
 */

import http from 'k6/http';
import { check } from 'k6';
import { Counter, Rate } from 'k6/metrics';
import { randomUUID } from 'k6/crypto';

const BASE = __ENV.BASE_URL || 'http://localhost:8081';
const SCENARIO = (__ENV.SCENARIO || 'read').toLowerCase();
const RATE = Number(__ENV.RATE || 200);
const RAMP = __ENV.RAMP || '20s';
const HOLD = __ENV.HOLD || '120s';
const P95_BUDGET = Number(__ENV.P95_BUDGET || 50);
// Warmup runs burn JIT and connection pools; their summary must not overwrite
// the artifact from the measured run. k6 v2 removed --no-summary, so the
// script itself decides whether to emit one.
const WARMUP = __ENV.WARMUP === 'true';

const ok = new Counter('cap_ok');
const blocked = new Counter('cap_blocked');
const serverErrors = new Rate('cap_server_error_rate');
const unexpected = new Rate('cap_unexpected_status_rate');

// Ramp to the target in two steps rather than one cliff, so the JIT and
// connection pools reach steady state before the measured hold window.
const quarter = Math.max(1, Math.round(RATE / 4));

export const options = {
  discardResponseBodies: true,
  // k6 only computes the percentile keys it is asked for. Without this the
  // published summary would silently report p50/p99 as zero.
  summaryTrendStats: ['avg', 'min', 'med', 'p(50)', 'p(90)', 'p(95)', 'p(99)', 'max'],
  scenarios: {
    capacity: {
      executor: 'ramping-arrival-rate',
      startRate: quarter,
      timeUnit: '1s',
      preAllocatedVUs: Math.max(50, Math.round(RATE * 0.2)),
      maxVUs: Math.max(500, RATE * 2),
      gracefulStop: '15s',
      stages: [
        { target: quarter, duration: '5s' },
        { target: RATE, duration: RAMP },
        { target: RATE, duration: HOLD },
      ],
    },
  },
  thresholds: {
    // A 5xx under load is a correctness failure, not a slow path.
    cap_server_error_rate: ['rate<0.001'],
    http_req_duration: [`p(95)<${P95_BUDGET}`],
  },
};

const JSON_HEADERS = { 'Content-Type': 'application/json' };

// Accepted status codes per scenario. In `enforced` mode the rate limiter
// legitimately returns 429; the capacity of the limiter is not the topic here.
const ACCEPT = {
  health: [200],
  read: [200, 429],
  write: [200, 201, 202, 400, 409, 429],
  'waf-clean': [200, 201, 202, 400, 409, 429],
  'waf-block': [403],
  mixed: [200, 201, 202, 400, 403, 409, 429],
};

function read() {
  return http.get(`${BASE}/api/account/balance?accountId=ACC123`);
}

function write(body) {
  const key = randomUUID();
  return http.post(`${BASE}/api/payments/transfer`, body(key), {
    headers: { ...JSON_HEADERS, 'Idempotency-Key': key },
  });
}

function paymentBody(key, note) {
  return JSON.stringify({
    fromAccountId: 'ACC123',
    toAccountId: 'ACC456',
    amount: 1,
    currency: 'INR',
    idempotencyKey: key,
    note: note || '',
  });
}

// 4 KB of benign JSON. Large enough that the WAF's normalisation cost shows up,
// small enough to stay under the 1 MB body limit.
const BIG_NOTE = 'a'.repeat(4096);

function attack() {
  return http.get(
    `${BASE}/api/account/balance?accountId=ACC123%27%20OR%20%271%27%3D%271`,
  );
}

function pick() {
  switch (SCENARIO) {
    case 'health':
      return http.get(`${BASE}/health`);
    case 'read':
      return read();
    case 'write':
      return write((k) => paymentBody(k));
    case 'waf-clean':
      return write((k) => paymentBody(k, BIG_NOTE));
    case 'waf-block':
      return attack();
    case 'mixed': {
      const roll = Math.random();
      if (roll < 0.85) return read();
      if (roll < 0.95) return write((k) => paymentBody(k));
      return attack();
    }
    default:
      throw new Error(
        `Unknown SCENARIO '${SCENARIO}'. Use health|read|write|waf-clean|waf-block|mixed.`,
      );
  }
}

export default function () {
  const res = pick();
  const allowed = ACCEPT[SCENARIO] || ACCEPT.read;

  const isServerError = res.status === 0 || res.status >= 500;
  const isExpected = allowed.includes(res.status);

  serverErrors.add(isServerError);
  unexpected.add(!isExpected);

  if (isServerError) return;
  if (res.status === 429 || res.status === 403) {
    blocked.add(1);
    return;
  }
  ok.add(1);

  check(res, {
    'no server error': (r) => r.status < 500 && r.status !== 0,
    'status in allowlist': (r) => allowed.includes(r.status),
  });
}

export function handleSummary(data) {
  if (WARMUP) return {};

  const m = data.metrics;
  const dur = m.http_req_duration ? m.http_req_duration.values : {};
  const summary = {
    scenario: SCENARIO,
    target_rate: RATE,
    held_for: HOLD,
    requests: m.http_reqs ? m.http_reqs.values.count : 0,
    achieved_rps: m.http_reqs ? m.http_reqs.values.rate : 0,
    failed_requests: m.http_req_failed ? m.http_req_failed.values.passes : 0,
    server_error_rate: m.cap_server_error_rate
      ? m.cap_server_error_rate.values.rate
      : 0,
    unexpected_status_rate: m.cap_unexpected_status_rate
      ? m.cap_unexpected_status_rate.values.rate
      : 0,
    p50_ms: dur['p(50)'] || 0,
    p95_ms: dur['p(95)'] || 0,
    p99_ms: dur['p(99)'] || 0,
    max_ms: dur.max || 0,
  };

  return {
    stdout: `\nCAPACITY ${JSON.stringify(summary)}\n`,
    'load-tests/results/capacity-summary.json': JSON.stringify(summary, null, 2),
  };
}
