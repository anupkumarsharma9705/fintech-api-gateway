import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter } from 'k6/metrics';

const serverErrors   = new Counter('server_errors_500');
const totalProcessed = new Counter('total_processed');

export const options = {
  stages: [
    { duration: '30s', target: 10 },
    { duration: '4m',  target: 10 },
    { duration: '30s', target: 0  },
  ],
  thresholds: {
    'server_errors_500': ['count==0'],
  },
};

const BASE = 'http://host.docker.internal:8081';

export default function () {

  // Pick request type based on VU number — no arrow function arrays
  const pick = Math.floor(Math.random() * 3);
  let res;

  if (pick === 0) {
    res = http.get(BASE + '/api/account/balance?accountId=ACC123');
  } else if (pick === 1) {
    res = http.get(BASE + '/api/account/profile?accountId=ACC123');
  } else {
    res = http.post(
      BASE + '/api/payments/transfer',
      '{"amount":"100","toAccount":"ACC456","note":"stability test"}',
      { headers: { 'Content-Type': 'application/json' } }
    );
  }

  totalProcessed.add(1);

  if (res.status === 500 || res.status === 502 || res.status === 503) {
    serverErrors.add(1);
  }

  check(res, {
    'no server crash': (r) => r.status !== 500,
  });

  sleep(0.2);
}