import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate } from 'k6/metrics';

const successCount  = new Counter('successful_requests');
const blockedCount  = new Counter('blocked_requests');
const errorRate     = new Rate('server_errors');

// Run from host: gateway is published on localhost:8081
const BASE_URL = __ENV.BASE_URL || 'http://localhost:8081';

export const options = {
  stages: [
    { duration: '30s', target: 5  },
    { duration: '1m',  target: 10 },
    { duration: '30s', target: 0  },
  ],
  thresholds: {
    http_req_duration: ['p(95)<2000'],
    server_errors:     ['rate<0.01'],
  },
};

export default function () {
  const res = http.get(
    `${BASE_URL}/api/account/balance?accountId=ACC123`
  );

  const ok = check(res, {
    'gateway responded':   (r) => r.status !== 0,
    'no server crash':     (r) => r.status !== 500,
    'processed or secured':(r) => [200, 429, 403].includes(r.status),
  });

  if (res.status === 200)        successCount.add(1);
  if (res.status === 429 || res.status === 403) blockedCount.add(1);
  errorRate.add(res.status === 500 || res.status === 0);

  sleep(1);
}