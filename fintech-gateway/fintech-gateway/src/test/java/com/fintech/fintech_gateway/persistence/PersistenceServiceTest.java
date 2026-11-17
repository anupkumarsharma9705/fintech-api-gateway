package com.fintech.fintech_gateway.persistence;

import com.fintech.fintech_gateway.persistence.entity.TrustHistoryEntity;
import com.fintech.fintech_gateway.persistence.repository.TrustHistoryRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PersistenceServiceTest {

    private final AuditWriteBuffer auditWriteBuffer = mock(AuditWriteBuffer.class);
    private final TrustHistoryRepository trustHistoryRepository = mock(TrustHistoryRepository.class);
    private final PersistenceService service =
            new PersistenceService(auditWriteBuffer, trustHistoryRepository);

    @Test
    void auditLogIsEnqueuedWithEveryField() {
        Instant timestamp = Instant.parse("2026-01-15T10:00:00Z");

        service.saveAuditLog(timestamp, "203.0.113.5", "POST", "/api/payments",
                75, "BLOCK", "SQL_INJECTION", 403, "tenant-a", "client-9",
                "req-1", 42L);

        ArgumentCaptor<AuditLogRecord> captor = ArgumentCaptor.forClass(AuditLogRecord.class);
        verify(auditWriteBuffer).enqueue(captor.capture(), eq(true));
        AuditLogRecord record = captor.getValue();
        assertEquals(timestamp, record.timestamp());
        assertEquals("203.0.113.5", record.ip());
        assertEquals("POST", record.method());
        assertEquals("/api/payments", record.uri());
        assertEquals(75, record.riskScore());
        assertEquals("BLOCK", record.decision());
        assertEquals("SQL_INJECTION", record.attackType());
        assertEquals(403, record.statusCode());
        assertEquals("tenant-a", record.tenantId());
        assertEquals("client-9", record.clientId());
        assertEquals("req-1", record.requestId());
        assertEquals(42L, record.latencyMs());
    }

    @Test
    void criticalOverloadIsNeverSampled() {
        service.saveAuditLog(Instant.now(), "1.2.3.4", "POST", "/compliance/export",
                100, "BLOCK", "OPS_AUTH_FAILURE", 401,
                "default", null, "req-2", 1L);

        verify(auditWriteBuffer).enqueue(any(AuditLogRecord.class), eq(true));
    }

    @Test
    void sampledRecordsUseTheSampledDataset() {
        service.saveAuditLog(new AuditLogRecord(Instant.now(), "1.2.3.4", "GET",
                "/api/account/balance", 0, "ALLOW", null, 200,
                "default", null, "req-3", 1L), false);

        verify(auditWriteBuffer).enqueue(any(AuditLogRecord.class), eq(false));
    }

    @Test
    void enqueueFailureIsSwallowed() {
        org.mockito.Mockito.doThrow(new IllegalStateException("buffer closed"))
                .when(auditWriteBuffer).enqueue(any(AuditLogRecord.class), eq(true));

        assertDoesNotThrow(() -> service.saveAuditLog(Instant.now(), "1.2.3.4",
                "GET", "/api/account/balance", 0, "ALLOW", null, 200,
                "default", null, "req-4", 1L));
    }

    @Test
    void trustHistoryIsPersistedWithTenantAndEveryField() {
        service.saveTrustHistory("tenant-a", "203.0.113.5", "MEDIUM", "LOW",
                "Blocked 1 times");

        ArgumentCaptor<TrustHistoryEntity> captor =
                ArgumentCaptor.forClass(TrustHistoryEntity.class);
        verify(trustHistoryRepository).save(captor.capture());
        TrustHistoryEntity entity = captor.getValue();
        assertEquals("tenant-a", entity.getTenantId());
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
                service.saveTrustHistory("tenant-a", "203.0.113.5", "MEDIUM", "HIGH", "clean"));
    }

    @Test
    void auditWriteIsNeverSentToTheJpaRepository() {
        service.saveAuditLog(Instant.now(), "1.2.3.4", "GET", "/api/account/balance",
                0, "ALLOW", null, 200, "default", null, "req-5", 1L);

        verify(trustHistoryRepository, never()).save(any(TrustHistoryEntity.class));
    }
}
