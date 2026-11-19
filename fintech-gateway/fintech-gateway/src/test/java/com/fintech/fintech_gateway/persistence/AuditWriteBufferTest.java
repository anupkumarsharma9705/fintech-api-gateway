package com.fintech.fintech_gateway.persistence;

import com.fintech.fintech_gateway.config.GatewayProperties;
import com.fintech.fintech_gateway.support.TestProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.PreparedStatement;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuditWriteBufferTest {

    private JdbcTemplate jdbcTemplate;
    private SimpleMeterRegistry registry;
    private GatewayProperties properties;
    private AuditWriteBuffer buffer;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        registry = new SimpleMeterRegistry();
        properties = TestProperties.defaults();
        properties.getAudit().setCriticalQueueCapacity(10);
        properties.getAudit().setSampledQueueCapacity(10);
        properties.getAudit().setWriteBatchSize(500);
        buffer = new AuditWriteBuffer(jdbcTemplate, properties, registry);
    }

    @Test
    void emptyQueuesDoNotTouchTheDatabase() {
        assertFalse(buffer.flushOnce());
        verify(jdbcTemplate, never()).batchUpdate(anyString(), any(BatchPreparedStatementSetter.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void everyColumnIsMappedAndCriticalRowsAreWrittenFirst() throws Exception {
        buffer.enqueue(record("sampled-req", "GET", 200), false);
        buffer.enqueue(record("critical-req", "POST", 403), true);

        assertTrue(buffer.flushOnce());

        ArgumentCaptor<BatchPreparedStatementSetter> captor =
                ArgumentCaptor.forClass(BatchPreparedStatementSetter.class);
        verify(jdbcTemplate).batchUpdate(eq(AuditWriteBuffer.INSERT_SQL), captor.capture());
        BatchPreparedStatementSetter setter = captor.getValue();
        assertEquals(2, setter.getBatchSize());

        // Drain order is critical first even though the sampled row was queued first.
        PreparedStatement critical = mock(PreparedStatement.class);
        setter.setValues(critical, 0);
        verify(critical).setString(11, "critical-req");
        verify(critical, never()).setString(11, "sampled-req");

        PreparedStatement sampled = mock(PreparedStatement.class);
        setter.setValues(sampled, 1);
        verify(sampled).setString(11, "sampled-req");

        verify(critical).setString(2, "203.0.113.5");
        verify(critical).setString(3, "POST");
        verify(critical).setString(4, "/api/account/balance");
        verify(critical).setInt(5, 80);
        verify(critical).setString(6, "BLOCK");
        verify(critical).setString(7, (String) null);
        verify(critical).setInt(8, 403);
        verify(critical).setString(9, "tenant-a");
        verify(critical).setString(10, "client-9");
        verify(critical).setLong(12, 12L);
        verify(critical).setObject(eq(1), any(Object.class));

        assertEquals(1.0, registry.counter("gateway.audit.persisted",
                "dataset", "critical").count());
        assertEquals(1.0, registry.counter("gateway.audit.persisted",
                "dataset", "sampled").count());
    }

    @Test
    @SuppressWarnings("unchecked")
    void aSingleFailureIsRetriedOnceAndSucceeds() {
        when(jdbcTemplate.batchUpdate(anyString(), any(BatchPreparedStatementSetter.class)))
                .thenThrow(new DataAccessResourceFailureException("deadlock"))
                .thenReturn(new int[]{1});
        buffer.enqueue(record("retry-ok", "GET", 200), true);

        assertTrue(buffer.flushOnce());

        verify(jdbcTemplate, times(2))
                .batchUpdate(anyString(), any(BatchPreparedStatementSetter.class));
        assertEquals(1.0, registry.counter("gateway.audit.persisted",
                "dataset", "critical").count());
        assertEquals(0.0, registry.counter("gateway.audit.write.failures").count());
        assertEquals(0.0, registry.counter("gateway.audit.dropped",
                "dataset", "critical", "reason", "write_failure").count());
    }

    @Test
    @SuppressWarnings("unchecked")
    void aRepeatedFailureIsCountedAndTheBatchIsDropped() {
        when(jdbcTemplate.batchUpdate(anyString(), any(BatchPreparedStatementSetter.class)))
                .thenThrow(new DataAccessResourceFailureException("down"));
        buffer.enqueue(record("dropped", "GET", 200), false);

        assertTrue(buffer.flushOnce());

        verify(jdbcTemplate, times(2))
                .batchUpdate(anyString(), any(BatchPreparedStatementSetter.class));
        assertEquals(1.0, registry.counter("gateway.audit.write.failures").count());
        assertEquals(1.0, registry.counter("gateway.audit.dropped",
                "dataset", "sampled", "reason", "write_failure").count());
        assertEquals(0.0, registry.counter("gateway.audit.persisted",
                "dataset", "sampled").count());
    }

    @Test
    void aFullQueueDropsWithoutBlockingTheCaller() {
        properties.getAudit().setCriticalQueueCapacity(1);
        registry = new SimpleMeterRegistry();
        buffer = new AuditWriteBuffer(jdbcTemplate, properties, registry);

        buffer.enqueue(record("first", "GET", 200), true);
        buffer.enqueue(record("second", "GET", 200), true);

        assertEquals(1.0, registry.counter("gateway.audit.dropped",
                "dataset", "critical", "reason", "queue_full").count());
        assertEquals(1.0, registry.get("gateway.audit.queue.depth")
                .tag("dataset", "critical").gauge().value());
    }

    @Test
    void rowsStillBufferedAtShutdownAreCountedNotSilentlyLost() {
        buffer.enqueue(record("critical-one", "GET", 200), true);
        buffer.enqueue(record("critical-two", "GET", 200), true);
        buffer.enqueue(record("sampled-one", "GET", 200), false);

        assertEquals(3, buffer.dropRemainingAtShutdown());

        assertEquals(2.0, registry.counter("gateway.audit.dropped",
                "dataset", "critical", "reason", "shutdown").count());
        assertEquals(1.0, registry.counter("gateway.audit.dropped",
                "dataset", "sampled", "reason", "shutdown").count());
        assertEquals(0.0, registry.get("gateway.audit.queue.depth")
                .tag("dataset", "critical").gauge().value()
                + registry.get("gateway.audit.queue.depth")
                .tag("dataset", "sampled").gauge().value(),
                "both queues must be empty after the shutdown accounting");

        // A second call must not double-count an already-drained queue.
        assertEquals(0, buffer.dropRemainingAtShutdown());
        assertEquals(2.0, registry.counter("gateway.audit.dropped",
                "dataset", "critical", "reason", "shutdown").count());
    }

    @Test
    void batchSizeCapsHowManyRowsAreDrainedAtOnce() {
        properties.getAudit().setWriteBatchSize(2);
        registry = new SimpleMeterRegistry();
        buffer = new AuditWriteBuffer(jdbcTemplate, properties, registry);
        buffer.enqueue(record("one", "GET", 200), true);
        buffer.enqueue(record("two", "GET", 200), true);
        buffer.enqueue(record("three", "GET", 200), true);

        buffer.flushOnce();

        assertEquals(1.0, registry.get("gateway.audit.queue.depth")
                .tag("dataset", "critical").gauge().value());
        assertEquals(2.0, registry.counter("gateway.audit.persisted",
                "dataset", "critical").count());
    }

    @Test
    @SuppressWarnings("unchecked")
    void coalescingCollectsRowsForTheFlushWindowInsteadOfWritingPerRow() throws Exception {
        properties.getAudit().setCriticalQueueCapacity(100);
        properties.getAudit().setSampledQueueCapacity(100);
        properties.getAudit().setWriteFlushInterval(java.time.Duration.ofMillis(300));
        registry = new SimpleMeterRegistry();
        buffer = new AuditWriteBuffer(jdbcTemplate, properties, registry);

        java.util.concurrent.atomic.AtomicBoolean wrote =
                new java.util.concurrent.atomic.AtomicBoolean();
        Thread flusher = new Thread(() -> wrote.set(buffer.flushOnce(true)));
        flusher.start();
        for (int i = 0; i < 20; i++) {
            buffer.enqueue(record("coalesced-" + i, "GET", 200), true);
            Thread.sleep(3);
        }
        flusher.join(2_000);

        assertFalse(flusher.isAlive(), "coalescing must end at the flush interval");
        assertTrue(wrote.get());
        ArgumentCaptor<BatchPreparedStatementSetter> captor =
                ArgumentCaptor.forClass(BatchPreparedStatementSetter.class);
        verify(jdbcTemplate, times(1))
                .batchUpdate(eq(AuditWriteBuffer.INSERT_SQL), captor.capture());
        assertEquals(20, captor.getValue().getBatchSize());
        assertEquals(20.0, registry.counter("gateway.audit.persisted",
                "dataset", "critical").count());
    }

    @Test
    void invalidConfigurationIsRejectedAtConstruction() {
        GatewayProperties invalid = TestProperties.defaults();
        invalid.getAudit().setCriticalQueueCapacity(0);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new AuditWriteBuffer(jdbcTemplate, invalid, registry));

        GatewayProperties zeroBatch = TestProperties.defaults();
        zeroBatch.getAudit().setWriteBatchSize(0);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new AuditWriteBuffer(jdbcTemplate, zeroBatch, registry));
    }

    private AuditLogRecord record(String requestId, String method, int status) {
        return new AuditLogRecord(Instant.parse("2026-01-15T10:00:00Z"),
                "203.0.113.5", method, "/api/account/balance",
                80, status >= 400 ? "BLOCK" : "ALLOW", null, status,
                "tenant-a", "client-9", requestId, 12L);
    }

    @Test
    void recordsAreClampedToColumnWidths() {
        AuditLogRecord record = new AuditLogRecord(Instant.now(),
                "1".repeat(100), "M".repeat(40), "u".repeat(3000),
                0, "D".repeat(40), "A".repeat(100), 200,
                "t".repeat(100), "c".repeat(300), "r".repeat(300), 1L);

        assertEquals(AuditLogRecord.IP_MAX, record.ip().length());
        assertEquals(AuditLogRecord.METHOD_MAX, record.method().length());
        assertEquals(AuditLogRecord.URI_MAX, record.uri().length());
        assertEquals(AuditLogRecord.DECISION_MAX, record.decision().length());
        assertEquals(AuditLogRecord.ATTACK_TYPE_MAX, record.attackType().length());
        assertEquals(AuditLogRecord.TENANT_MAX, record.tenantId().length());
        assertEquals(AuditLogRecord.CLIENT_MAX, record.clientId().length());
        assertEquals(AuditLogRecord.REQUEST_ID_MAX, record.requestId().length());
    }

    @Test
    void nullFieldsFallBackToSafeDefaults() {
        AuditLogRecord record = new AuditLogRecord(null, null, null, null,
                0, null, null, 200, null, null, null, 0L);

        assertEquals("", record.ip());
        assertEquals("", record.method());
        assertEquals("", record.uri());
        assertEquals("ALLOW", record.decision());
        assertEquals("default", record.tenantId());
        assertEquals(null, record.attackType());
        assertEquals(null, record.clientId());
        assertEquals(null, record.requestId());
        org.junit.jupiter.api.Assertions.assertNotNull(record.timestamp());
    }

}
