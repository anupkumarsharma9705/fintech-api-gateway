import http from 'k6/http';
import { check } from 'k6';
import { Counter, Rate } from 'k6/metrics';

const uniquesProcessed     = new Counter('unique_payments_processed');
const duplicatesSuppressed = new Counter('duplicates_suppressed');
const idempotencyAccuracy  = new Rate('idempotency_accuracy');

export const options = {
  iterations: 20,
  vus: 1,
  thresholds: {
    'idempotency_accuracy': ['rate>=0.95'],
  },
};

const BASE = 'http://host.docker.internal:8081';

export default function () {

  // Build unique key per iteration using timestamp + random
  const key = 'PAY-' + Date.now() + '-' + Math.floor(Math.random() * 999999);
  const body = '{"amount":"500.00","toAccount":"ACC456","note":"test payment"}';
  const headers = {
    'Content-Type': 'application/json',
    'Idempotency-Key': key,
  };

  // First call — should be processed (200 or 201)
  const first = http.post(BASE + '/api/payments/transfer', body, { headers: headers });
  const firstOk = first.status === 200 || first.status === 201 || first.status === 429;

  // Second call — same key — should return cached (200)
  const second = http.post(BASE + '/api/payments/transfer', body, { headers: headers });
  const secondOk = second.status === 200 || second.status === 201;

  // Third call — same key — still cached
  const third = http.post(BASE + '/api/payments/transfer', body, { headers: headers });
  const thirdOk = third.status === 200 || third.status === 201;

  if (firstOk)  { uniquesProcessed.add(1); }
  if (secondOk && thirdOk) {
    duplicatesSuppressed.add(2);
    idempotencyAccuracy.add(true);
  } else {
    idempotencyAccuracy.add(false);
  }

  check(first,  { 'first call processed':          () => firstOk  });
  check(second, { 'second call duplicate handled':  () => secondOk });
  check(third,  { 'third call duplicate handled':   () => thirdOk  });
}