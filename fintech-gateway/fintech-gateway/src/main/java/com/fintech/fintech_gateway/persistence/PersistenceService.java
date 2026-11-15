package com.fintech.fintech_gateway.persistence;

import com.fintech.fintech_gateway.persistence.entity.AuditLogEntity;
import com.fintech.fintech_gateway.persistence.entity.TrustHistoryEntity;
import com.fintech.fintech_gateway.persistence.repository.AuditLogRepository;
import com.fintech.fintech_gateway.persistence.repository.TrustHistoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * Persists the security audit trail on the bounded auditExecutor pool.
 * Writes are best-effort: losing an audit row must not fail the client
 * request, but the structured log line is always emitted first, so the
 * event is never completely lost.
 */
@Service
public class PersistenceService {

    private static final Logger log = LoggerFactory.getLogger(PersistenceService.class);

    private final AuditLogRepository auditLogRepository;
    private final TrustHistoryRepository trustHistoryRepository;

    public PersistenceService(AuditLogRepository auditLogRepository,
                              TrustHistoryRepository trustHistoryRepository) {
        this.auditLogRepository = auditLogRepository;
        this.trustHistoryRepository = trustHistoryRepository;
    }

    @Async("auditExecutor")
    public void saveAuditLog(Instant timestamp, String ip, String method,
                             String uri, int riskScore, String decision,
                             String attackType, int statusCode,
                             String tenantId, String clientId, String requestId,
                             long latencyMs) {
        try {
            auditLogRepository.save(new AuditLogEntity(
                    timestamp, ip, method, uri, riskScore, decision,
                    attackType, statusCode, tenantId, clientId, requestId, latencyMs));
        } catch (RuntimeException e) {
            log.error("Failed to persist audit log for request {}", requestId, e);
        }
    }

    @Async("auditExecutor")
    public void saveTrustHistory(String ip, String previousTier,
                                 String newTier, String reason) {
        try {
            trustHistoryRepository.save(new TrustHistoryEntity(
                    ip, previousTier, newTier, reason));
        } catch (RuntimeException e) {
            log.error("Failed to persist trust history for {}", ip, e);
        }
    }
}
