package com.fintech.fintech_gateway.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "trust_history")
public class TrustHistoryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(nullable = false, length = 64)
    private String ip;

    @Column(name = "previous_tier", nullable = false, length = 16)
    private String previousTier;

    @Column(name = "new_tier", nullable = false, length = 16)
    private String newTier;

    @Column(nullable = false, length = 512)
    private String reason;

    @Column(nullable = false, columnDefinition = "datetime(6)")
    private Instant timestamp;

    public TrustHistoryEntity() {
    }

    public TrustHistoryEntity(String tenantId, String ip, String previousTier,
                              String newTier, String reason) {
        this.tenantId = tenantId;
        this.ip = ip;
        this.previousTier = previousTier;
        this.newTier = newTier;
        this.reason = reason;
        this.timestamp = Instant.now();
    }

    public Long getId() { return id; }
    public String getTenantId() { return tenantId; }
    public String getIp() { return ip; }
    public String getPreviousTier() { return previousTier; }
    public String getNewTier() { return newTier; }
    public String getReason() { return reason; }
    public Instant getTimestamp() { return timestamp; }
}
