import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend } from 'k6/metrics';

const cleanLatency = new Trend('gateway_clean_latency');

export const options = {
  stages: [
    { duration: '10s', target: 5 },
    { duration: '30s', target: 10 },
    { duration: '10s', target: 0 },
  ],
  thresholds: {
    'gateway_clean_latency': ['p(95)<100'],
  },
};

const BASE = __ENV.BASE_URL || 'http://localhost:8081';

export default function () {
  // Health endpoint bypasses the admission pipeline - clean latency
  const res = http.get(`${BASE}/health`);

  if (res.status === 200) {
    cleanLatency.add(res.timings.duration);
  }

  check(res, {
    'health ok': (r) => r.status === 200,
    'no server error': (r) => r.status !== 500,
  });

  sleep(0.1);
}