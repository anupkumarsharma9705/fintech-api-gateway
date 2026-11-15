import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend } from 'k6/metrics';

const cleanLatency = new Trend('gateway_clean_latency');

export const options = {
  stages: [
    { duration: '30s', target: 5 },
    { duration: '60s', target: 5 },
    { duration: '10s', target: 0 },
  ],
  thresholds: {
    'gateway_clean_latency': ['p(95)<500'],
  },
};

const BASE = 'http://host.docker.internal:8081';

export default function () {

  // Random account ID to avoid single IP accumulating risk score
  const id = 'ACC' + Math.floor(Math.random() * 9999);
  const res = http.get(BASE + '/api/account/balance?accountId=' + id);

  // Only record latency for requests that actually got through
  if (res.status === 200) {
    cleanLatency.add(res.timings.duration);
  }

  check(res, {
    'request processed': (r) => r.status === 200 || r.status === 429 || r.status === 403,
    'no server error':   (r) => r.status !== 500,
  });

  sleep(0.2);
}