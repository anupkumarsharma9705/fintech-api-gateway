package com.fintech.fintech_gateway.audit;

import com.fintech.fintech_gateway.audit.AuditLogger.AuditEvent;
import com.fintech.fintech_gateway.audit.AuditSamplingPolicy.Persistence;
import com.fintech.fintech_gateway.config.GatewayProperties;
import com.fintech.fintech_gateway.risk.RiskDecisionEngine.Decision;
import com.fintech.fintech_gateway.support.TestProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuditSamplingPolicyTest {

    @Test
    void fullRatePersistsEveryEventAndDisclosesNoSampling() {
        AuditSamplingPolicy policy = policy(1.0, "dev");

        assertEquals(Persistence.PERSIST_CRITICAL, policy.decide(cleanAllow("req-1")));
        assertFalse(policy.isSampled());
        assertEquals(1.0, policy.getSampleRate());
    }

    @Test
    void zeroRateSkipsCleanAllowTraffic() {
        AuditSamplingPolicy policy = policy(0.0, "dev");

        assertEquals(Persistence.SKIP, policy.decide(cleanAllow("req-1")));
        assertTrue(policy.isSampled());
    }

    @Test
    void samplingIsDeterministicForTheSameRequestId() {
        AuditSamplingPolicy policy = policy(0.5, "dev");
        AuditEvent event = cleanAllow("stable-request-id");

        Persistence first = policy.decide(event);
        for (int i = 0; i < 20; i++) {
            assertEquals(first, policy.decide(event));
        }
    }

    @Test
    void sampleRateIsHonouredWithinStatisticalTolerance() {
        AuditSamplingPolicy policy = policy(0.1, "dev");
        int selected = 0;
        int total = 20_000;
        for (int i = 0; i < total; i++) {
            if (policy.decide(cleanAllow("request-" + i)) == Persistence.PERSIST_SAMPLED) {
                selected++;
            }
        }

        double actual = selected / (double) total;
        assertTrue(actual > 0.085 && actual < 0.115,
                "expected ~0.10 sampled, got " + actual);
    }

    @Test
    void securityBlocksAreNeverSampled() {
        AuditSamplingPolicy policy = policy(0.0, "dev");

        assertEquals(Persistence.PERSIST_CRITICAL, policy.decide(new AuditEvent.Builder()
                .ip("203.0.113.5").method("GET").uri("/api/account/balance")
                .requestId("req-block").decision(Decision.BLOCK).statusCode(403)
                .build()));
        assertEquals(Persistence.PERSIST_CRITICAL, policy.decide(new AuditEvent.Builder()
                .ip("203.0.113.5").method("GET").uri("/api/account/balance")
                .requestId("req-throttle").decision(Decision.THROTTLE).statusCode(200)
                .build()));
    }

    @Test
    void errorResponsesAreNeverSampled() {
        AuditSamplingPolicy policy = policy(0.0, "dev");

        assertEquals(Persistence.PERSIST_CRITICAL, policy.decide(new AuditEvent.Builder()
                .ip("203.0.113.5").method("GET").uri("/api/account/balance")
                .requestId("req-500").decision(Decision.ALLOW).statusCode(500)
                .build()));
    }

    @Test
    void wafDetectionsAreNeverSampled() {
        AuditSamplingPolicy policy = policy(0.0, "dev");

        assertEquals(Persistence.PERSIST_CRITICAL, policy.decide(new AuditEvent.Builder()
                .ip("203.0.113.5").method("POST").uri("/api/account/balance")
                .requestId("req-waf").decision(Decision.ALLOW).statusCode(200)
                .attackType("SQL_INJECTION").build()));
    }

    @Test
    void idempotencyEventsAreNeverSampled() {
        AuditSamplingPolicy policy = policy(0.0, "dev");

        assertEquals(Persistence.PERSIST_CRITICAL, policy.decide(new AuditEvent.Builder()
                .ip("203.0.113.5").method("POST").uri("/api/orders")
                .requestId("req-idem").decision(Decision.ALLOW).statusCode(201)
                .idempotencyInvolved(true).build()));
    }

    @Test
    void paymentPathsAreNeverSampled() {
        AuditSamplingPolicy policy = policy(0.0, "dev");

        for (String uri : new String[]{
                "/api/payments", "/api/payments/", "/api/payments/transfer",
                "/api/transfers", "/api/transactions/42", "/api/refunds/9"}) {
            assertEquals(Persistence.PERSIST_CRITICAL, policy.decide(new AuditEvent.Builder()
                    .ip("203.0.113.5").method("POST").uri(uri)
                    .requestId("req-payment").decision(Decision.ALLOW).statusCode(200)
                    .build()), uri);
        }
    }

    @Test
    void percentEncodedPathsAreNeverSampled() {
        AuditSamplingPolicy policy = policy(0.0, "dev");

        assertEquals(Persistence.PERSIST_CRITICAL, policy.decide(new AuditEvent.Builder()
                .ip("203.0.113.5").method("POST").uri("/api/%70ayments/transfer")
                .requestId("req-encoded").decision(Decision.ALLOW).statusCode(200)
                .build()));
    }

    @Test
    void blankRequestIdIsNeverSampled() {
        AuditSamplingPolicy policy = policy(0.0, "dev");

        assertEquals(Persistence.PERSIST_CRITICAL, policy.decide(new AuditEvent.Builder()
                .ip("203.0.113.5").method("GET").uri("/api/account/balance")
                .decision(Decision.ALLOW).statusCode(200).build()));
    }

    @Test
    void nonPaymentPathIsStillEligibleForSampling() {
        AuditSamplingPolicy policy = policy(0.0, "dev");

        assertEquals(Persistence.SKIP, policy.decide(cleanAllow("request-on-account-path")));
    }

    @Test
    void prodProfileRefusesAnEffectivelyDisabledAuditTrail() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> policy(0.001, "prod"));

        assertTrue(error.getMessage().contains("at least"));
    }

    @Test
    void prodProfileAcceptsTheDocumentedFloor() {
        AuditSamplingPolicy policy = policy(0.01, "prod");

        assertTrue(policy.isSampled());
        assertEquals(0.01, policy.getSampleRate());
    }

    @Test
    void nonProdProfileMayDisableSamplingEntirely() {
        assertEquals(0.0, policy(0.0, "dev").getSampleRate());
    }

    @Test
    void outOfRangeRatesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> policy(1.5, "dev"));
        assertThrows(IllegalArgumentException.class, () -> policy(-0.1, "dev"));
        assertThrows(IllegalArgumentException.class, () -> policy(Double.NaN, "dev"));
    }

    @Test
    void aChosenRequestIdCannotSteerTheSampleWithoutTheSalt() {
        byte[] saltA = new byte[32];
        byte[] saltB = new byte[32];
        for (int i = 0; i < saltB.length; i++) {
            saltB[i] = (byte) (i + 1);
        }
        AuditSamplingPolicy first = policy(0.5, saltA);
        AuditSamplingPolicy second = policy(0.5, saltB);

        boolean differed = false;
        for (int i = 0; i < 200 && !differed; i++) {
            AuditEvent event = cleanAllow("chosen-request-" + i);
            if (first.decide(event) != second.decide(event)) {
                differed = true;
            }
        }
        assertTrue(differed,
                "the per-process salt must change a caller-chosen id's selection, "
                        + "otherwise an attacker can precompute which reads escape the sample");
    }

    private static AuditSamplingPolicy policy(double rate, byte[] salt) {
        GatewayProperties properties = TestProperties.defaults();
        properties.getAudit().setSampleRate(rate);
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("dev");
        return new AuditSamplingPolicy(properties, environment, salt);
    }

    private static AuditSamplingPolicy policy(double rate, String... profiles) {
        GatewayProperties properties = TestProperties.defaults();
        properties.getAudit().setSampleRate(rate);
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profiles);
        return new AuditSamplingPolicy(properties, environment);
    }

    private static AuditEvent cleanAllow(String requestId) {
        return new AuditEvent.Builder()
                .ip("203.0.113.5")
                .method("GET")
                .uri("/api/account/balance")
                .requestId(requestId)
                .decision(Decision.ALLOW)
                .statusCode(200)
                .build();
    }
}
