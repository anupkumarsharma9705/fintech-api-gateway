import http from 'k6/http';
import { check } from 'k6';
import { Counter, Rate } from 'k6/metrics';

const truePositives  = new Counter('attacks_correctly_blocked');
const falsePositives = new Counter('clean_requests_wrongly_blocked');
const falseNegatives = new Counter('attacks_that_slipped_through');
const detectionRate  = new Rate('attack_detection_rate');

export const options = {
  iterations: 50,
  vus: 1,
  thresholds: {
    'attack_detection_rate':          ['rate>=0.99'],
    'clean_requests_wrongly_blocked': ['count==0'],
  },
};

const BASE = 'http://host.docker.internal:8081';

export default function () {

  // --- SQL INJECTION TESTS ---
  const sqli1 = http.post(
    BASE + '/api/payments/transfer',
    '{"amount":"100","toAccount":"ACC456 OR 1=1; --"}',
    { headers: { 'Content-Type': 'application/json' } }
  );
  const sqli1Blocked = sqli1.status === 400 || sqli1.status === 403;
  if (sqli1Blocked) { truePositives.add(1); detectionRate.add(true); }
  else              { falseNegatives.add(1); detectionRate.add(false); }
  check(sqli1, { 'SQLi classic blocked': () => sqli1Blocked });

  const sqli2 = http.post(
    BASE + '/api/payments/transfer',
    '{"amount":"0 UNION SELECT * FROM users","toAccount":"ACC456"}',
    { headers: { 'Content-Type': 'application/json' } }
  );
  const sqli2Blocked = sqli2.status === 400 || sqli2.status === 403;
  if (sqli2Blocked) { truePositives.add(1); detectionRate.add(true); }
  else              { falseNegatives.add(1); detectionRate.add(false); }
  check(sqli2, { 'SQLi union blocked': () => sqli2Blocked });

  const sqli3 = http.post(
    BASE + '/api/payments/transfer',
    '{"note":"test; DROP TABLE payments; --","amount":"100","toAccount":"ACC456"}',
    { headers: { 'Content-Type': 'application/json' } }
  );
  const sqli3Blocked = sqli3.status === 400 || sqli3.status === 403;
  if (sqli3Blocked) { truePositives.add(1); detectionRate.add(true); }
  else              { falseNegatives.add(1); detectionRate.add(false); }
  check(sqli3, { 'SQLi drop blocked': () => sqli3Blocked });

  // --- XSS TESTS ---
  const xss1 = http.post(
    BASE + '/api/payments/transfer',
    '{"note":"<script>alert(1)</script>","amount":"100","toAccount":"ACC456"}',
    { headers: { 'Content-Type': 'application/json' } }
  );
  const xss1Blocked = xss1.status === 400 || xss1.status === 403;
  if (xss1Blocked) { truePositives.add(1); detectionRate.add(true); }
  else             { falseNegatives.add(1); detectionRate.add(false); }
  check(xss1, { 'XSS script blocked': () => xss1Blocked });

  const xss2 = http.post(
    BASE + '/api/payments/transfer',
    '{"note":"<img onerror=alert(1) src=x>","amount":"100","toAccount":"ACC456"}',
    { headers: { 'Content-Type': 'application/json' } }
  );
  const xss2Blocked = xss2.status === 400 || xss2.status === 403;
  if (xss2Blocked) { truePositives.add(1); detectionRate.add(true); }
  else             { falseNegatives.add(1); detectionRate.add(false); }
  check(xss2, { 'XSS img blocked': () => xss2Blocked });

  // --- PATH TRAVERSAL TESTS ---
  const pt1 = http.post(
    BASE + '/api/payments/transfer',
    '{"file":"../../etc/passwd","amount":"100","toAccount":"ACC456"}',
    { headers: { 'Content-Type': 'application/json' } }
  );
  const pt1Blocked = pt1.status === 400 || pt1.status === 403;
  if (pt1Blocked) { truePositives.add(1); detectionRate.add(true); }
  else            { falseNegatives.add(1); detectionRate.add(false); }
  check(pt1, { 'Path traversal blocked': () => pt1Blocked });

  // --- CLEAN REQUEST --- must NOT be blocked
  const clean = http.post(
    BASE + '/api/payments/transfer',
    '{"amount":"500.00","toAccount":"ACC789","note":"monthly rent"}',
    { headers: { 'Content-Type': 'application/json' } }
  );
  const wronglyBlocked = clean.status === 400 || clean.status === 403;
  if (wronglyBlocked) { falsePositives.add(1); }
  check(clean, {
    'clean payment not blocked': () => !wronglyBlocked,
  });
}