package com.fintech.fintech_gateway.audit;

import com.fintech.fintech_gateway.audit.AuditLogger.AuditEvent;
import com.fintech.fintech_gateway.metrics.GatewayMetrics;
import com.fintech.fintech_gateway.persistence.AuditLogRecord;
import com.fintech.fintech_gateway.persistence.PersistenceService;
import com.fintech.fintech_gateway.risk.RiskDecisionEngine.Decision;
import com.fintech.fintech_gateway.support.TestProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class AuditLoggerTest {

    private final PersistenceService persistenceService = mock(PersistenceService.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final GatewayMetrics metrics = new GatewayMetrics(registry);
    private final AuditLogger auditLogger = new AuditLogger(persistenceService,
            fullRatePolicy(), metrics);

    @Test
    void sanitizesAttackerControlledFieldsBeforePersistence() {
        AuditEvent event = new AuditEvent.Builder()
                .ip("203.0.113.5\r\nINJECTED")
                .method("POST\n")
                .uri("/api/payments\r\nFAKE | admin=true")
                .tenantId("tenant\t1")
                .clientId("client\n1")
                .requestId("req\n1")
                .riskScore(80)
                .decision(Decision.BLOCK)
                .attackType("SQL_INJECTION")
                .statusCode(403)
                .latencyMs(12)
                .build();

        auditLogger.log(event);

        ArgumentCaptor<AuditLogRecord> captor = ArgumentCaptor.forClass(AuditLogRecord.class);
        verify(persistenceService).saveAuditLog(captor.capture(), eq(true));
        AuditLogRecord record = captor.getValue();
        assertEquals("203.0.113.5__INJECTED", record.ip());
        assertEquals("POST_", record.method());
        assertEquals("/api/payments__FAKE | admin=true", record.uri());
        assertEquals(80, record.riskScore());
        assertEquals("BLOCK", record.decision());
        assertEquals("SQL_INJECTION", record.attackType());
        assertEquals(403, record.statusCode());
        assertEquals("tenant_1", record.tenantId());
        assertEquals("client_1", record.clientId());
        assertEquals("req_1", record.requestId());
        assertEquals(12L, record.latencyMs());
    }

    @Test
    void persistenceFailureDoesNotFailTheRequest() {
        doThrow(new IllegalStateException("buffer closed")).when(persistenceService)
                .saveAuditLog(any(AuditLogRecord.class), eq(true));

        assertDoesNotThrow(() -> auditLogger.log(new AuditEvent.Builder()
                .ip("203.0.113.5")
                .method("GET")
                .uri("/api/account/balance")
                .decision(Decision.ALLOW)
                .statusCode(200)
                .build()));
    }

    @Test
    void sampledOutEventIsNotPersistedButIsCounted() {
        AuditLogger sampled = new AuditLogger(persistenceService, zeroRatePolicy(), metrics);

        sampled.log(new AuditEvent.Builder()
                .ip("203.0.113.5")
                .method("GET")
                .uri("/api/account/balance")
                .requestId("req-sampled-out")
                .decision(Decision.ALLOW)
                .statusCode(200)
                .build());

        verify(persistenceService, never()).saveAuditLog(any(AuditLogRecord.class), any(Boolean.class));
        assertEquals(1.0, registry.counter("gateway.audit.sampled_out").count());
    }

    @Test
    void sampledEventUsesTheSampledDataset() {
        AuditLogger sampled = new AuditLogger(persistenceService, zeroRatePolicy(), metrics);

        sampled.log(new AuditEvent.Builder()
                .ip("203.0.113.5")
                .method("POST")
                .uri("/api/payments")
                .requestId("req-never-sampled")
                .decision(Decision.ALLOW)
                .statusCode(200)
                .build());

        verify(persistenceService).saveAuditLog(any(AuditLogRecord.class), eq(true));
    }

    @Test
    void eventDefaultsAreSafe() {
        AuditEvent event = new AuditEvent.Builder().build();

        assertEquals(Decision.ALLOW, event.getDecision());
        assertEquals("default", event.getTenantId());
        assertNull(event.getClientId());
        assertEquals("", event.getRequestId());
        assertEquals(0, event.getRiskScore());
        assertEquals(0, event.getStatusCode());
        assertEquals(false, event.isIdempotencyInvolved());
    }

    @Test
    void sanitizeReplacesControlCharacters() {
        assertEquals("a_b_c", AuditLogger.sanitize("a\rb\nc"));
        assertEquals("a_b", AuditLogger.sanitize("a\u0000b"));
        assertEquals("a_b", AuditLogger.sanitize("a\u001Fb"));
        assertEquals("a_b", AuditLogger.sanitize("a\u007Fb"));
    }

    @Test
    void sanitizeHandlesNullAndOverlongValues() {
        assertEquals("", AuditLogger.sanitize(null));
        String overlong = "x".repeat(5000);
        assertEquals(2048, AuditLogger.sanitize(overlong).length());
    }

    @Test
    void sanitizePreservesOrdinaryText() {
        assertEquals("/api/payments/transfer?currency=INR",
                AuditLogger.sanitize("/api/payments/transfer?currency=INR"));
    }

    private static AuditSamplingPolicy fullRatePolicy() {
        return new AuditSamplingPolicy(TestProperties.defaults(), new MockEnvironment());
    }

    private static AuditSamplingPolicy zeroRatePolicy() {
        var properties = TestProperties.defaults();
        properties.getAudit().setSampleRate(0.0);
        return new AuditSamplingPolicy(properties, new MockEnvironment());
    }
}
