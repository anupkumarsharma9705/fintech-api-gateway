-- Baseline schema for the gateway's security evidence store.
-- audit_logs is the durable compliance/audit trail; trust_history records
-- every automated or operator-driven trust-tier change.
--
-- This migration is intentionally additive and idempotent for fresh
-- environments. Existing pre-Flyway development databases should be reset
-- once (docker compose down -v) because Hibernate validate will reject the
-- old ddl-auto schema.

CREATE TABLE IF NOT EXISTS audit_logs (
    id BIGINT NOT NULL AUTO_INCREMENT,
    timestamp DATETIME(6) NOT NULL,
    ip VARCHAR(64) NOT NULL,
    method VARCHAR(16) NOT NULL,
    uri VARCHAR(2048) NOT NULL,
    risk_score INT NOT NULL,
    decision VARCHAR(16) NOT NULL,
    attack_type VARCHAR(64) NULL,
    status_code INT NOT NULL,
    tenant_id VARCHAR(64) NOT NULL DEFAULT 'default',
    request_id VARCHAR(128) NULL,
    latency_ms BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_audit_timestamp (timestamp),
    KEY idx_audit_ip_timestamp (ip, timestamp),
    KEY idx_audit_tenant_timestamp (tenant_id, timestamp),
    KEY idx_audit_decision (decision)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS trust_history (
    id BIGINT NOT NULL AUTO_INCREMENT,
    ip VARCHAR(64) NOT NULL,
    previous_tier VARCHAR(16) NOT NULL,
    new_tier VARCHAR(16) NOT NULL,
    reason VARCHAR(512) NOT NULL,
    timestamp DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_trust_ip_timestamp (ip, timestamp),
    KEY idx_trust_timestamp (timestamp)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
