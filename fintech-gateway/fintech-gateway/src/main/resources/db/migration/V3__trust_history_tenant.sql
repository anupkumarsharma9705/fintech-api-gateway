-- Trust-tier mutations are tenant-scoped after the limiter/trust keys stopped
-- being global per IP. Existing rows are attributed to the default tenant,
-- which is honest for single-tenant deployments and clearly identifiable as
-- legacy data otherwise.

ALTER TABLE trust_history
    ADD COLUMN tenant_id VARCHAR(64) NOT NULL DEFAULT 'default' AFTER id,
    ADD KEY idx_trust_tenant_timestamp (tenant_id, timestamp);
