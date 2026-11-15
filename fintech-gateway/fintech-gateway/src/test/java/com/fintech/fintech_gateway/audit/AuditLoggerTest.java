package com.fintech.fintech_gateway.audit;

import com.fintech.fintech_gateway.audit.AuditLogger.AuditEvent;
import com.fintech.fintech_gateway.persistence.PersistenceService;
import com.fintech.fintech_gateway.risk.RiskDecisionEngine.Decision;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AuditLoggerTest {

    private final PersistenceService persistenceService = mock(PersistenceService.class);
    private final AuditLogger auditLogger = new AuditLogger(persistenceService);

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

        verify(persistenceService).saveAuditLog(any(Instant.class),
                eq("203.0.113.5__INJECTED"), eq("POST_"),
                eq("/api/payments__FAKE | admin=true"), eq(80), eq("BLOCK"),
                eq("SQL_INJECTION"), eq(403), eq("tenant_1"), eq("client_1"),
                eq("req_1"), eq(12L));
    }

    @Test
    void persistenceFailureDoesNotFailTheRequest() {
        doThrow(new IllegalStateException("db down")).when(persistenceService)
                .saveAuditLog(any(), any(), any(), any(), anyInt(), any(), any(),
                        anyInt(), any(), any(), any(), anyLong());

        assertDoesNotThrow(() -> auditLogger.log(new AuditEvent.Builder()
                .ip("203.0.113.5")
                .method("GET")
                .uri("/api/account/balance")
                .decision(Decision.ALLOW)
                .statusCode(200)
                .build()));
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
}
