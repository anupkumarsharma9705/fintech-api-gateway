package com.fintech.fintech_gateway.audit;

import com.fintech.fintech_gateway.audit.AuditSamplingPolicy.Persistence;
import com.fintech.fintech_gateway.metrics.GatewayMetrics;
import com.fintech.fintech_gateway.persistence.AuditLogRecord;
import com.fintech.fintech_gateway.persistence.PersistenceService;
import com.fintech.fintech_gateway.risk.RiskDecisionEngine.Decision;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.regex.Pattern;

/**
 * Writes the security audit trail to the structured log and the durable
 * evidence store.
 *
 * <p>The structured line is emitted for every request. The database row may be
 * sampled for clean ALLOW traffic when {@code gateway.audit.sample-rate < 1.0};
 * the line records {@code persisted=false} so log consumers can tell the
 * difference. Security, payment, idempotency and operator events are never
 * sampled.</p>
 *
 * <p>Attacker-controlled values are sanitized before logging so a crafted URI
 * cannot forge log lines or break downstream log parsers.</p>
 */
@Component
public class AuditLogger {

    private static final Logger log = LoggerFactory.getLogger("AUDIT");
    private static final Pattern LOG_BREAK = Pattern.compile("[\\r\\n\\t\\u0000-\\u001F\\u007F]");
    private static final int MAX_FIELD_LENGTH = 2048;
    private static final int MAX_CLIENT_ID_LENGTH = 128;

    private final PersistenceService persistenceService;
    private final AuditSamplingPolicy samplingPolicy;
    private final GatewayMetrics metrics;

    public AuditLogger(PersistenceService persistenceService,
                       AuditSamplingPolicy samplingPolicy,
                       GatewayMetrics metrics) {
        this.persistenceService = persistenceService;
        this.samplingPolicy = samplingPolicy;
        this.metrics = metrics;
    }

    public void log(AuditEvent event) {
        String uri = sanitize(event.getUri());
        String ip = sanitize(event.getIp());
        String method = sanitize(event.getMethod());
        String tenantId = sanitize(event.getTenantId());
        String clientId = truncate(sanitize(event.getClientId()), MAX_CLIENT_ID_LENGTH);
        String requestId = sanitize(event.getRequestId());

        Persistence persistence = samplingPolicy.decide(event);
        boolean persisted = persistence != Persistence.SKIP;

        log.info("AUDIT | requestId={} | tenant={} | client={} | timestamp={} | ip={} | method={} | uri={} | riskScore={} | decision={} | attackType={} | statusCode={} | latencyMs={} | idempotency={} | persisted={}",
                requestId,
                tenantId,
                clientId,
                event.getTimestamp(),
                ip,
                method,
                uri,
                event.getRiskScore(),
                event.getDecision(),
                event.getAttackType() != null ? event.getAttackType() : "NONE",
                event.getStatusCode(),
                event.getLatencyMs(),
                event.isIdempotencyInvolved(),
                persisted);

        if (!persisted) {
            metrics.recordAuditSampledOut();
            return;
        }

        try {
            persistenceService.saveAuditLog(new AuditLogRecord(
                    event.getTimestamp(),
                    ip,
                    method,
                    uri,
                    event.getRiskScore(),
                    event.getDecision().name(),
                    event.getAttackType(),
                    event.getStatusCode(),
                    tenantId,
                    clientId.isEmpty() ? null : clientId,
                    requestId,
                    event.getLatencyMs()),
                    persistence == Persistence.PERSIST_CRITICAL);
        } catch (RuntimeException e) {
            // Enqueue is non-blocking; this guard only covers shutdown races.
            log.error("Failed to enqueue audit log", e);
        }
    }

    static String sanitize(String value) {
        if (value == null) {
            return "";
        }
        String cleaned = LOG_BREAK.matcher(value).replaceAll("_");
        return cleaned.length() > MAX_FIELD_LENGTH
                ? cleaned.substring(0, MAX_FIELD_LENGTH)
                : cleaned;
    }

    private static String truncate(String value, int maxLength) {
        return value.length() > maxLength ? value.substring(0, maxLength) : value;
    }

    public static class AuditEvent {
        private final Instant timestamp;
        private final String ip;
        private final String method;
        private final String uri;
        private final int riskScore;
        private final Decision decision;
        private final String attackType;
        private final int statusCode;
        private final String tenantId;
        private final String clientId;
        private final String requestId;
        private final long latencyMs;
        private final boolean idempotencyInvolved;

        private AuditEvent(Builder builder) {
            this.timestamp = builder.timestamp != null ? builder.timestamp : Instant.now();
            this.ip = builder.ip;
            this.method = builder.method;
            this.uri = builder.uri;
            this.riskScore = builder.riskScore;
            this.decision = builder.decision != null ? builder.decision : Decision.ALLOW;
            this.attackType = builder.attackType;
            this.statusCode = builder.statusCode;
            this.tenantId = builder.tenantId != null ? builder.tenantId : "default";
            this.clientId = builder.clientId;
            this.requestId = builder.requestId != null ? builder.requestId : "";
            this.latencyMs = builder.latencyMs;
            this.idempotencyInvolved = builder.idempotencyInvolved;
        }

        public static class Builder {
            private Instant timestamp;
            private String ip;
            private String method;
            private String uri;
            private int riskScore;
            private Decision decision;
            private String attackType;
            private int statusCode;
            private String tenantId;
            private String clientId;
            private String requestId;
            private long latencyMs;
            private boolean idempotencyInvolved;

            public Builder timestamp(Instant timestamp) { this.timestamp = timestamp; return this; }
            public Builder ip(String ip) { this.ip = ip; return this; }
            public Builder method(String method) { this.method = method; return this; }
            public Builder uri(String uri) { this.uri = uri; return this; }
            public Builder riskScore(int riskScore) { this.riskScore = riskScore; return this; }
            public Builder decision(Decision decision) { this.decision = decision; return this; }
            public Builder attackType(String attackType) { this.attackType = attackType; return this; }
            public Builder statusCode(int statusCode) { this.statusCode = statusCode; return this; }
            public Builder tenantId(String tenantId) { this.tenantId = tenantId; return this; }
            public Builder clientId(String clientId) { this.clientId = clientId; return this; }
            public Builder requestId(String requestId) { this.requestId = requestId; return this; }
            public Builder latencyMs(long latencyMs) { this.latencyMs = latencyMs; return this; }
            public Builder idempotencyInvolved(boolean idempotencyInvolved) {
                this.idempotencyInvolved = idempotencyInvolved;
                return this;
            }
            public AuditEvent build() { return new AuditEvent(this); }
        }

        public Instant getTimestamp() { return timestamp; }
        public String getIp() { return ip; }
        public String getMethod() { return method; }
        public String getUri() { return uri; }
        public int getRiskScore() { return riskScore; }
        public Decision getDecision() { return decision; }
        public String getAttackType() { return attackType; }
        public int getStatusCode() { return statusCode; }
        public String getTenantId() { return tenantId; }
        public String getClientId() { return clientId; }
        public String getRequestId() { return requestId; }
        public long getLatencyMs() { return latencyMs; }
        public boolean isIdempotencyInvolved() { return idempotencyInvolved; }
    }
}
