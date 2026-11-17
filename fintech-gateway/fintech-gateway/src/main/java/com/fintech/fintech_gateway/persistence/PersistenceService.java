package com.fintech.fintech_gateway.persistence;

import com.fintech.fintech_gateway.persistence.entity.TrustHistoryEntity;
import com.fintech.fintech_gateway.persistence.repository.TrustHistoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * Persistence boundary for the audit trail.
 *
 * <p>Audit rows are handed to {@link AuditWriteBuffer}, which writes them on a
 * dedicated batch thread. No database connection is ever acquired on a Tomcat
 * request thread. Trust-history writes are best-effort and run on their own
 * bounded executor, separate from the audit writer, so a slow trust-history
 * insert cannot delay security evidence.</p>
 */
@Service
public class PersistenceService {

    private static final Logger log = LoggerFactory.getLogger(PersistenceService.class);

    private final AuditWriteBuffer auditWriteBuffer;
    private final TrustHistoryRepository trustHistoryRepository;

    public PersistenceService(AuditWriteBuffer auditWriteBuffer,
                              TrustHistoryRepository trustHistoryRepository) {
        this.auditWriteBuffer = auditWriteBuffer;
        this.trustHistoryRepository = trustHistoryRepository;
    }

    /**
     * Enqueues a sanitized audit row. The sampling decision was already made
     * by {@code AuditSamplingPolicy}; {@code critical} only selects the queue
     * and dataset, never whether the row is kept.
     */
    public void saveAuditLog(AuditLogRecord record, boolean critical) {
        try {
            auditWriteBuffer.enqueue(record, critical);
        } catch (RuntimeException e) {
            // The structured AUDIT line was already emitted. Persistence is
            // best-effort and must never change a request decision.
            log.error("Failed to enqueue audit record", e);
        }
    }

    /**
     * Critical-only overload used by filters that reject a request before the
     * normal audit pipeline runs (for example operator-authentication
     * failures). These events are never sampled.
     */
    public void saveAuditLog(Instant timestamp, String ip, String method,
                             String uri, int riskScore, String decision,
                             String attackType, int statusCode,
                             String tenantId, String clientId, String requestId,
                             long latencyMs) {
        try {
            auditWriteBuffer.enqueue(new AuditLogRecord(
                    timestamp, ip, method, uri, riskScore, decision,
                    attackType, statusCode, tenantId, clientId, requestId, latencyMs), true);
        } catch (RuntimeException e) {
            log.error("Failed to enqueue critical audit record", e);
        }
    }

    @Async("trustExecutor")
    public void saveTrustHistory(String tenantId, String ip, String previousTier,
                                 String newTier, String reason) {
        try {
            trustHistoryRepository.save(new TrustHistoryEntity(
                    tenantId, ip, previousTier, newTier, reason));
        } catch (RuntimeException e) {
            log.error("Failed to persist trust history for tenant {} ip {}", tenantId, ip, e);
        }
    }
}
