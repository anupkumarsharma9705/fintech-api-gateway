-- Identify the authenticated OAuth2 client in the audit trail.
-- Existing rows remain NULL; the compliance summary reports them as legacy
-- evidence rows rather than pretending the client identity was known.

ALTER TABLE audit_logs
    ADD COLUMN client_id VARCHAR(128) NULL AFTER tenant_id,
    ADD KEY idx_audit_client_timestamp (client_id, timestamp);
