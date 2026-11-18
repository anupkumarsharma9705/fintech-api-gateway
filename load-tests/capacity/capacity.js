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
import exec from 'k6/execution';
import { randomUUID } from 'k6/crypto';

const BASE = __ENV.BASE_URL || 'http://localhost:8081';
const SCENARIO = (__ENV.SCENARIO || 'read').toLowerCase();
const RATE = Number(__ENV.RATE || 200);
// RAMP=0 measures a constant-arrival window: the whole run is the hold, so the
// reported rate is the offered rate rather than a ramp-diluted average.
const RAMP = __ENV.RAMP || '20s';
const HOLD = __ENV.HOLD || '120s';
const P95_BUDGET = Number(__ENV.P95_BUDGET || 50);
// The first seconds of a constant-arrival run are cold: new TCP connections,
// JIT, and pool growth happen at the full offered rate. A whole-window p95
// then describes the start-up transient, not the gateway. Requests are tagged
// cold/steady so the summary can report both. The default matches the
// runner's 10 s warmup.
const STEADY_AFTER_SECONDS = parseDuration(__ENV.STEADY_AFTER || '10s');
// A cold start that takes more than a second is a defect, not a measurement
// artifact. This threshold also materialises the cold submetric in the
// summary, so the transient is reported next to steady state.
const COLD_P95_BUDGET = Number(__ENV.COLD_P95_BUDGET || 1000);
// Explicit VU ceiling. It is recorded with the result because a run that
// exhausts it measures the generator, not the gateway.
const MAX_VUS = Number(__ENV.MAX_VUS || 0);
// Warmup runs burn JIT and connection pools; their summary must not overwrite
// the artifact from the measured run. k6 v2 removed --no-summary, so the
// script itself decides whether to emit one.
const WARMUP = __ENV.WARMUP === 'true';

function parseDuration(value) {
  const m = /^(\d+(?:\.\d+)?)(ms|s|m|h)$/.exec(String(value).trim());
  if (!m) throw new Error(`Unsupported duration '${value}' (use 30s, 1m, 1h)`);
  const n = Number(m[1]);
  if (m[2] === 'ms') return n / 1000;
  if (m[2] === 'm') return n * 60;
  if (m[2] === 'h') return n * 3600;
  return n;
}

const NO_RAMP = RAMP === '0' || RAMP === '0s';
const HOLD_SECONDS = parseDuration(HOLD);
const RAMP_SECONDS = NO_RAMP ? 0 : parseDuration(RAMP);
const TARGET_VUS = MAX_VUS > 0 ? MAX_VUS : Math.max(500, RATE * 2);

const ok = new Counter('cap_ok');
const blocked = new Counter('cap_blocked');
const serverErrors = new Rate('cap_server_error_rate');
const unexpected = new Rate('cap_unexpected_status_rate');

// Ramp to the target in two steps rather than one cliff, so the JIT and
// connection pools reach steady state before the measured hold window.
const quarter = Math.max(1, Math.round(RATE / 4));

// Analytic schedule size. Comparing it with executed iterations is how a run
// proves the generator actually offered the load it claims to have offered.
const scheduledIterations = NO_RAMP
  ? Math.round(RATE * HOLD_SECONDS)
  : Math.round(quarter * 5 + ((quarter + RATE) / 2) * RAMP_SECONDS + RATE * HOLD_SECONDS);

const capacityScenario = NO_RAMP
  ? {
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: HOLD,
      preAllocatedVUs: Math.max(50, Math.round(RATE * 0.2)),
      maxVUs: TARGET_VUS,
      gracefulStop: '15s',
    }
  : {
      executor: 'ramping-arrival-rate',
      startRate: quarter,
      timeUnit: '1s',
      preAllocatedVUs: Math.max(50, Math.round(RATE * 0.2)),
      maxVUs: TARGET_VUS,
      gracefulStop: '15s',
      stages: [
        { target: quarter, duration: '5s' },
        { target: RATE, duration: RAMP },
        { target: RATE, duration: HOLD },
      ],
    };

export const options = {
  discardResponseBodies: true,
  // k6 only computes the percentile keys it is asked for. Without this the
  // published summary would silently report p50/p99 as zero.
  summaryTrendStats: ['count', 'avg', 'min', 'med', 'p(50)', 'p(90)', 'p(95)', 'p(99)', 'max'],
  scenarios: {
    capacity: capacityScenario,
  },
  thresholds: {
    // A 5xx under load is a correctness failure, not a slow path.
    cap_server_error_rate: ['rate<0.001'],
    http_req_duration: [`p(95)<${P95_BUDGET}`],
    // The SLO is about steady state. Thresholding the whole window lets a
    // cold-start spike fail a run that is healthy once warm, and lets a warm
    // regression hide behind a fast start.
    'http_req_duration{phase:steady}': [`p(95)<${P95_BUDGET}`],
    'http_req_duration{phase:cold}': [`p(95)<${COLD_P95_BUDGET}`],
  },
};

const JSON_HEADERS = { 'Content-Type': 'application/json' };

// Every request carries its phase so the summary can separate cold start from
// steady state. exec.scenario.startTime is the scenario epoch, not the VU's,
// so VUs that start late are still classified correctly.
function req(method, url, body, params) {
  const p = params || {};
  const steady = Date.now() - exec.scenario.startTime >= STEADY_AFTER_SECONDS * 1000;
  p.tags = { ...(p.tags || {}), phase: steady ? 'steady' : 'cold' };
  return http.request(method, url, body, p);
}

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
  return req('GET', `${BASE}/api/account/balance?accountId=ACC123`);
}

function write(body) {
  const key = randomUUID();
  return req('POST', `${BASE}/api/payments/transfer`, body(key), {
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
  return req(
    'GET',
    `${BASE}/api/account/balance?accountId=ACC123%27%20OR%20%271%27%3D%271`,
  );
}

function pick() {
  switch (SCENARIO) {
    case 'health':
      return req('GET', `${BASE}/health`);
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
  // Tag-filtered submetrics are materialised because options.thresholds
  // references them. If a future k6 stops doing that, the fields stay zero and
  // the evidence gate rejects the run rather than silently publishing a
  // whole-window number as steady state.
  const steady = m['http_req_duration{phase:steady}']
    ? m['http_req_duration{phase:steady}'].values
    : {};
  const cold = m['http_req_duration{phase:cold}']
    ? m['http_req_duration{phase:cold}'].values
    : {};
  const count = (name) => (m[name] ? m[name].values.count : 0);
  const requests = count('http_reqs');
  const iterations = count('iterations');
  // Non-zero means k6 had no free VU at the offered rate: the shortfall is at
  // least partly the generator's, so the run cannot claim a ceiling.
  const dropped = count('dropped_iterations');
  const vusMax = m.vus_max ? (m.vus_max.values.max || m.vus_max.values.value) : 0;

  const summary = {
    scenario: SCENARIO,
    target_rate: RATE,
    held_for: HOLD,
    ramp: RAMP,
    window: NO_RAMP ? 'constant' : 'ramping',
    max_vus: TARGET_VUS,
    scheduled_iterations: scheduledIterations,
    requests,
    achieved_rps: m.http_reqs ? m.http_reqs.values.rate : 0,
    iterations,
    executed_ratio: scheduledIterations > 0 ? iterations / scheduledIterations : 0,
    dropped_iterations: dropped,
    generator_limited: dropped > 0,
    vus_max_observed: vusMax,
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
    steady_after_s: STEADY_AFTER_SECONDS,
    steady_requests: steady.count || 0,
    steady_p50_ms: steady['p(50)'] || 0,
    steady_p95_ms: steady['p(95)'] || 0,
    steady_p99_ms: steady['p(99)'] || 0,
    cold_p95_ms: cold['p(95)'] || 0,
  };

  return {
    stdout: `\nCAPACITY ${JSON.stringify(summary)}\n`,
    'load-tests/results/capacity-summary.json': JSON.stringify(summary, null, 2),
  };
}
