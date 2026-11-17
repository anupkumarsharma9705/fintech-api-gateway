package com.fintech.fintech_gateway.persistence;

import com.fintech.fintech_gateway.config.GatewayProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bounded, non-blocking audit write buffer.
 *
 * <p>The request thread only performs {@link BlockingQueue#offer}: it never
 * opens a connection, never waits for a flush and never runs an INSERT. One
 * background thread drains the queues into JDBC batches. Security- and
 * payment-critical events have their own queue, so a flood of sampled traffic
 * cannot delay them.</p>
 *
 * <p>Backpressure is explicit: when a queue is full the event is dropped,
 * counted, and logged loudly (rate-limited). Blocking a Tomcat thread on the
 * database is what previously pinned the executor and broke the request path;
 * it is deliberately not an option here.</p>
 */
@Component
public class AuditWriteBuffer {

    private static final Logger log = LoggerFactory.getLogger(AuditWriteBuffer.class);

    static final String INSERT_SQL = """
            INSERT INTO audit_logs
                (timestamp, ip, method, uri, risk_score, decision, attack_type,
                 status_code, tenant_id, client_id, request_id, latency_ms)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private static final long WARN_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(5);
    private static final long RETRY_PAUSE_MILLIS = 200;

    private final JdbcTemplate jdbcTemplate;
    private final BlockingQueue<AuditLogRecord> criticalQueue;
    private final BlockingQueue<AuditLogRecord> sampledQueue;
    private final int batchSize;
    private final long flushIntervalMillis;

    private final Counter criticalPersisted;
    private final Counter sampledPersisted;
    private final Counter writeFailures;
    private final Counter criticalDroppedFull;
    private final Counter sampledDroppedFull;
    private final Counter criticalDroppedWrite;
    private final Counter sampledDroppedWrite;

    private final AtomicLong droppedTotal = new AtomicLong();
    private final AtomicLong lastWarnNanos = new AtomicLong();

    private volatile boolean running;
    private Thread writer;

    public AuditWriteBuffer(JdbcTemplate jdbcTemplate,
                            GatewayProperties properties,
                            MeterRegistry registry) {
        GatewayProperties.Audit audit = properties.getAudit();
        if (audit.getCriticalQueueCapacity() <= 0 || audit.getSampledQueueCapacity() <= 0) {
            throw new IllegalArgumentException(
                    "gateway.audit queue capacities must be positive");
        }
        if (audit.getWriteBatchSize() <= 0) {
            throw new IllegalArgumentException("gateway.audit.write-batch-size must be positive");
        }
        if (audit.getWriteFlushInterval() == null
                || audit.getWriteFlushInterval().isNegative()
                || audit.getWriteFlushInterval().isZero()) {
            throw new IllegalArgumentException(
                    "gateway.audit.write-flush-interval must be positive");
        }

        this.jdbcTemplate = jdbcTemplate;
        this.criticalQueue = new ArrayBlockingQueue<>(audit.getCriticalQueueCapacity());
        this.sampledQueue = new ArrayBlockingQueue<>(audit.getSampledQueueCapacity());
        this.batchSize = audit.getWriteBatchSize();
        this.flushIntervalMillis = Math.max(1, audit.getWriteFlushInterval().toMillis());

        this.criticalPersisted = counter(registry, "gateway.audit.persisted",
                "dataset", "critical");
        this.sampledPersisted = counter(registry, "gateway.audit.persisted",
                "dataset", "sampled");
        this.writeFailures = registry.counter("gateway.audit.write.failures");
        this.criticalDroppedFull = counter(registry, "gateway.audit.dropped",
                "dataset", "critical", "reason", "queue_full");
        this.sampledDroppedFull = counter(registry, "gateway.audit.dropped",
                "dataset", "sampled", "reason", "queue_full");
        this.criticalDroppedWrite = counter(registry, "gateway.audit.dropped",
                "dataset", "critical", "reason", "write_failure");
        this.sampledDroppedWrite = counter(registry, "gateway.audit.dropped",
                "dataset", "sampled", "reason", "write_failure");

        Gauge.builder("gateway.audit.queue.depth", criticalQueue, BlockingQueue::size)
                .tag("dataset", "critical")
                .register(registry);
        Gauge.builder("gateway.audit.queue.depth", sampledQueue, BlockingQueue::size)
                .tag("dataset", "sampled")
                .register(registry);
    }

    /**
     * Called from request threads. Never blocks and never throws for a full
     * queue: dropping a row is preferable to stalling the request that has
     * already been served.
     */
    public void enqueue(AuditLogRecord record, boolean critical) {
        BlockingQueue<AuditLogRecord> queue = critical ? criticalQueue : sampledQueue;
        if (queue.offer(record)) {
            return;
        }
        if (critical) {
            criticalDroppedFull.increment();
        } else {
            sampledDroppedFull.increment();
        }
        warnDrop(critical ? "critical" : "sampled", "queue_full");
    }

    @PostConstruct
    void start() {
        running = true;
        writer = new Thread(this::runWriter, "audit-writer");
        writer.setDaemon(false);
        writer.start();
    }

    @PreDestroy
    void stop() {
        running = false;
        Thread current = writer;
        if (current == null) {
            return;
        }
        current.interrupt();
        try {
            current.join(TimeUnit.SECONDS.toMillis(15));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (current.isAlive()) {
            log.error("Audit writer did not stop within 15s; remaining rows are being "
                    + "flushed on the shutdown thread");
            drainWithDeadline(System.nanoTime() + TimeUnit.SECONDS.toNanos(5));
        }
        if (hasPending()) {
            int remaining = criticalQueue.size() + sampledQueue.size();
            droppedTotal.addAndGet(remaining);
            log.error("Dropping {} buffered audit rows at shutdown because the database "
                    + "did not accept them in time", remaining);
        }
    }

    private void runWriter() {
        while (running || hasPending()) {
            try {
                if (!flushOnce()) {
                    if (!running) {
                        break;
                    }
                    Thread.sleep(flushIntervalMillis);
                }
            } catch (InterruptedException e) {
                if (!running) {
                    // Shutdown interrupt: keep draining until the queues are empty.
                    Thread.interrupted();
                    continue;
                }
                Thread.currentThread().interrupt();
                break;
            } catch (RuntimeException e) {
                log.error("Audit writer failed unexpectedly; continuing", e);
                sleepQuietly();
            }
        }
    }

    private void drainWithDeadline(long deadlineNanos) {
        while (hasPending() && System.nanoTime() < deadlineNanos) {
            flushOnce();
        }
    }

    /**
     * Drains at most one batch and writes it. Package-visible so the batching
     * and retry behaviour can be tested deterministically without a thread.
     *
     * @return true when at least one row was drained
     */
    synchronized boolean flushOnce() {
        List<AuditLogRecord> batch = new ArrayList<>(batchSize);
        criticalQueue.drainTo(batch, batchSize);
        int criticalCount = batch.size();
        if (batch.size() < batchSize) {
            sampledQueue.drainTo(batch, batchSize - batch.size());
        }
        int sampledCount = batch.size() - criticalCount;
        if (batch.isEmpty()) {
            return false;
        }

        if (write(batch)) {
            if (criticalCount > 0) {
                criticalPersisted.increment(criticalCount);
            }
            if (sampledCount > 0) {
                sampledPersisted.increment(sampledCount);
            }
        } else {
            writeFailures.increment();
            if (criticalCount > 0) {
                criticalDroppedWrite.increment(criticalCount);
            }
            if (sampledCount > 0) {
                sampledDroppedWrite.increment(sampledCount);
            }
            droppedTotal.addAndGet(batch.size());
            log.error("Dropped {} audit rows after the batch write failed twice "
                    + "(critical={}, sampled={})", batch.size(), criticalCount, sampledCount);
        }
        return true;
    }

    private boolean write(List<AuditLogRecord> batch) {
        try {
            batchInsert(batch);
            return true;
        } catch (DataAccessException first) {
            log.warn("Audit batch write failed for {} rows; retrying once", batch.size(), first);
        }
        sleepQuietly();
        try {
            batchInsert(batch);
            return true;
        } catch (DataAccessException second) {
            log.error("Audit batch retry failed for {} rows", batch.size(), second);
            return false;
        }
    }

    private void batchInsert(List<AuditLogRecord> batch) {
        jdbcTemplate.batchUpdate(INSERT_SQL, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                AuditLogRecord row = batch.get(i);
                ps.setObject(1, LocalDateTime.ofInstant(row.timestamp(), ZoneOffset.UTC));
                ps.setString(2, row.ip());
                ps.setString(3, row.method());
                ps.setString(4, row.uri());
                ps.setInt(5, row.riskScore());
                ps.setString(6, row.decision());
                ps.setString(7, row.attackType());
                ps.setInt(8, row.statusCode());
                ps.setString(9, row.tenantId());
                ps.setString(10, row.clientId());
                ps.setString(11, row.requestId());
                ps.setLong(12, row.latencyMs());
            }

            @Override
            public int getBatchSize() {
                return batch.size();
            }
        });
    }

    private boolean hasPending() {
        return !criticalQueue.isEmpty() || !sampledQueue.isEmpty();
    }

    private void sleepQuietly() {
        try {
            Thread.sleep(RETRY_PAUSE_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void warnDrop(String dataset, String reason) {
        long dropped = droppedTotal.incrementAndGet();
        long now = System.nanoTime();
        long last = lastWarnNanos.get();
        if (last != 0 && now - last < WARN_INTERVAL_NANOS) {
            return;
        }
        if (lastWarnNanos.compareAndSet(last, now)) {
            log.error("Audit buffer is full ({}); dropped {} event(s) so far "
                            + "(reason={}). Increase capacity or fix the database.",
                    dataset, dropped, reason);
        }
    }

    private static Counter counter(MeterRegistry registry, String name, String... tags) {
        return Counter.builder(name).tags(tags).register(registry);
    }
}
