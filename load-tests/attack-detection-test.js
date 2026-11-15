import http from 'k6/http';
import { check, sleep } from 'k6';

export const options = {
  vus: 1,
  iterations: 20,
  thresholds: {
    checks: ['rate>0.85'],
  },
};

export default function () {
  const base = 'http://host.docker.internal:8081';
  const ts   = `${__VU}-${__ITER}-${Date.now()}`;

  // ── SQL Injection ──────────────────────────────────────
  const sqlRes = http.post(
    `${base}/api/payments/transfer`,
    JSON.stringify({
      fromAccountId: "ACC123' OR '1'='1",
      toAccountId:   'ACC456',
      amount:        5000,
      currency:      'INR',
      idempotencyKey:`sql-${ts}`,
    }),
    {
      headers: {
        'Content-Type':    'application/json',
        'Idempotency-Key': `sql-${ts}`,
      },
    }
  );
  check(sqlRes, {
    'SQL injection → 400': (r) => r.status === 400,
  });

  sleep(0.5);

  // ── XSS ───────────────────────────────────────────────
  const xssRes = http.post(
    `${base}/api/payments/transfer`,
    JSON.stringify({
      fromAccountId: "<script>alert('xss')</script>",
      toAccountId:   'ACC456',
      amount:        5000,
      currency:      'INR',
      idempotencyKey:`xss-${ts}`,
    }),
    {
      headers: {
        'Content-Type':    'application/json',
        'Idempotency-Key': `xss-${ts}`,
      },
    }
  );
  check(xssRes, {
    'XSS → 400': (r) => r.status === 400,
  });

  sleep(0.5);

  // ── Clean Request ─────────────────────────────────────
  const cleanRes = http.post(
    `${base}/api/payments/transfer`,
    JSON.stringify({
      fromAccountId: 'ACC123',
      toAccountId:   'ACC456',
      amount:        5000,
      currency:      'INR',
      idempotencyKey:`clean-${ts}`,
    }),
    {
      headers: {
        'Content-Type':    'application/json',
        'Idempotency-Key': `clean-${ts}`,
      },
    }
  );
  check(cleanRes, {
    'clean request → 200 or 429': (r) =>
      r.status === 200 || r.status === 429,
  });

  sleep(1);
}