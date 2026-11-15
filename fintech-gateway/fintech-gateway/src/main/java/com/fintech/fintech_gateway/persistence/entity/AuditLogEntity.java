package com.fintech.fintech_gateway.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "audit_logs")
public class AuditLogEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, columnDefinition = "datetime(6)")
    private Instant timestamp;

    @Column(nullable = false, length = 64)
    private String ip;

    @Column(nullable = false, length = 16)
    private String method;

    @Column(nullable = false, length = 2048)
    private String uri;

    @Column(name = "risk_score", nullable = false)
    private int riskScore;

    @Column(nullable = false, length = 16)
    private String decision;

    @Column(name = "attack_type", length = 64)
    private String attackType;

    @Column(name = "status_code", nullable = false)
    private int statusCode;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "client_id", length = 128)
    private String clientId;

    @Column(name = "request_id", length = 128)
    private String requestId;

    @Column(name = "latency_ms", nullable = false)
    private long latencyMs;

    public AuditLogEntity() {
    }

    public AuditLogEntity(Instant timestamp, String ip, String method,
                          String uri, int riskScore, String decision,
                          String attackType, int statusCode,
                          String tenantId, String clientId, String requestId,
                          long latencyMs) {
        this.timestamp = timestamp;
        this.ip = ip;
        this.method = method;
        this.uri = uri;
        this.riskScore = riskScore;
        this.decision = decision;
        this.attackType = attackType;
        this.statusCode = statusCode;
        this.tenantId = tenantId;
        this.clientId = clientId;
        this.requestId = requestId;
        this.latencyMs = latencyMs;
    }

    public Long getId() { return id; }
    public Instant getTimestamp() { return timestamp; }
    public String getIp() { return ip; }
    public String getMethod() { return method; }
    public String getUri() { return uri; }
    public int getRiskScore() { return riskScore; }
    public String getDecision() { return decision; }
    public String getAttackType() { return attackType; }
    public int getStatusCode() { return statusCode; }
    public String getTenantId() { return tenantId; }
    public String getClientId() { return clientId; }
    public String getRequestId() { return requestId; }
    public long getLatencyMs() { return latencyMs; }
}
