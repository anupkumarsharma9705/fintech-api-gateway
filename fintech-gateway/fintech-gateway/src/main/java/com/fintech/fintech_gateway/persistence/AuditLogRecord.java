package com.fintech.fintech_gateway.persistence;

import java.time.Instant;

/**
 * One audit row on its way to the batch writer.
 *
 * <p>Values are bounded to the column widths here, not in the database: a
 * single over-long attacker-controlled field must not make a 500-row batch
 * fail and discard the other 499 security events. {@code AuditLogger} already
 * sanitizes control characters; this is the last line of defence for width.</p>
 */
public record AuditLogRecord(Instant timestamp,
                             String ip,
                             String method,
                             String uri,
                             int riskScore,
                             String decision,
                             String attackType,
                             int statusCode,
                             String tenantId,
                             String clientId,
                             String requestId,
                             long latencyMs) {

    static final int IP_MAX = 64;
    static final int METHOD_MAX = 16;
    static final int URI_MAX = 2048;
    static final int DECISION_MAX = 16;
    static final int ATTACK_TYPE_MAX = 64;
    static final int TENANT_MAX = 64;
    static final int CLIENT_MAX = 128;
    static final int REQUEST_ID_MAX = 128;

    public AuditLogRecord {
        timestamp = timestamp != null ? timestamp : Instant.now();
        ip = truncate(ip, IP_MAX, "");
        method = truncate(method, METHOD_MAX, "");
        uri = truncate(uri, URI_MAX, "");
        decision = truncate(decision, DECISION_MAX, "ALLOW");
        attackType = attackType == null ? null : truncate(attackType, ATTACK_TYPE_MAX, null);
        tenantId = truncate(tenantId, TENANT_MAX, "default");
        clientId = clientId == null ? null : truncate(clientId, CLIENT_MAX, null);
        requestId = requestId == null ? null : truncate(requestId, REQUEST_ID_MAX, null);
    }

    private static String truncate(String value, int maxLength, String fallback) {
        if (value == null) {
            return fallback;
        }
        return value.length() > maxLength ? value.substring(0, maxLength) : value;
    }
}
