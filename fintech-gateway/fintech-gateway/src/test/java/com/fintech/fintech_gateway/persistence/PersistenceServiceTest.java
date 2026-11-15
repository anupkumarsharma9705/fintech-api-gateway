package com.fintech.fintech_gateway.persistence;

import com.fintech.fintech_gateway.persistence.entity.AuditLogEntity;
import com.fintech.fintech_gateway.persistence.entity.TrustHistoryEntity;
import com.fintech.fintech_gateway.persistence.repository.AuditLogRepository;
import com.fintech.fintech_gateway.persistence.repository.TrustHistoryRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PersistenceServiceTest {

    private final AuditLogRepository auditLogRepository = mock(AuditLogRepository.class);
    private final TrustHistoryRepository trustHistoryRepository = mock(TrustHistoryRepository.class);
    private final PersistenceService service =
            new PersistenceService(auditLogRepository, trustHistoryRepository);

    @Test
    void auditLogIsPersistedWithEveryField() {
        Instant timestamp = Instant.parse("2026-01-15T10:00:00Z");

        service.saveAuditLog(timestamp, "203.0.113.5", "POST", "/api/payments",
                75, "BLOCK", "SQL_INJECTION", 403, "tenant-a", "client-9",
                "req-1", 42L);

        ArgumentCaptor<AuditLogEntity> captor = ArgumentCaptor.forClass(AuditLogEntity.class);
        verify(auditLogRepository).save(captor.capture());
        AuditLogEntity entity = captor.getValue();
        assertEquals(timestamp, entity.getTimestamp());
        assertEquals("203.0.113.5", entity.getIp());
        assertEquals("POST", entity.getMethod());
        assertEquals("/api/payments", entity.getUri());
        assertEquals(75, entity.getRiskScore());
        assertEquals("BLOCK", entity.getDecision());
        assertEquals("SQL_INJECTION", entity.getAttackType());
        assertEquals(403, entity.getStatusCode());
        assertEquals("tenant-a", entity.getTenantId());
        assertEquals("client-9", entity.getClientId());
        assertEquals("req-1", entity.getRequestId());
        assertEquals(42L, entity.getLatencyMs());
    }

    @Test
    void auditRepositoryFailureIsSwallowed() {
        when(auditLogRepository.save(any(AuditLogEntity.class)))
                .thenThrow(new IllegalStateException("db down"));

        assertDoesNotThrow(() -> service.saveAuditLog(Instant.now(), "1.2.3.4",
                "GET", "/api/account/balance", 0, "ALLOW", null, 200,
                "default", null, "req-2", 1L));
    }

    @Test
    void trustHistoryIsPersistedWithEveryField() {
        service.saveTrustHistory("203.0.113.5", "MEDIUM", "LOW", "Blocked 1 times");

        ArgumentCaptor<TrustHistoryEntity> captor =
                ArgumentCaptor.forClass(TrustHistoryEntity.class);
        verify(trustHistoryRepository).save(captor.capture());
        TrustHistoryEntity entity = captor.getValue();
        assertEquals("203.0.113.5", entity.getIp());
        assertEquals("MEDIUM", entity.getPreviousTier());
        assertEquals("LOW", entity.getNewTier());
        assertEquals("Blocked 1 times", entity.getReason());
        assertNotNull(entity.getTimestamp());
    }

    @Test
    void trustHistoryRepositoryFailureIsSwallowed() {
        when(trustHistoryRepository.save(any(TrustHistoryEntity.class)))
                .thenThrow(new IllegalStateException("db down"));

        assertDoesNotThrow(() ->
                service.saveTrustHistory("203.0.113.5", "MEDIUM", "HIGH", "clean"));
    }
}
