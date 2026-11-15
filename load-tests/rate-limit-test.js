import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter } from 'k6/metrics';

const allowedCount  = new Counter('allowed_200');
const throttledCount= new Counter('throttled_429');
const blockedCount  = new Counter('blocked_403');

export const options = {
  stages: [
    { duration: '20s', target: 20  },
    { duration: '30s', target: 50  },
    { duration: '10s', target: 0   },
  ],
  thresholds: {
    checks: ['rate>0.99'],
  },
};

export default function () {
  const res = http.get(
    'http://host.docker.internal:8081/api/account/balance?accountId=ACC123'
  );

  check(res, {
    'no server crash': (r) => r.status !== 500 && r.status !== 0,
  });

  if (res.status === 200) allowedCount.add(1);
  if (res.status === 429) throttledCount.add(1);
  if (res.status === 403) blockedCount.add(1);

  sleep(0.1);
}