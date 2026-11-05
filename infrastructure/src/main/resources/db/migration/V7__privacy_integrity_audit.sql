CREATE TABLE privacy_integrity_audit (
    audit_id               UUID PRIMARY KEY,
    operation              VARCHAR(50) NOT NULL,
    account_id             UUID,
    operator_subject       VARCHAR(255) NOT NULL,
    reason                 VARCHAR(500) NOT NULL,
    outcome                VARCHAR(50) NOT NULL,
    verified_events        BIGINT,
    first_invalid_position BIGINT,
    occurred_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE OR REPLACE FUNCTION prevent_privacy_integrity_audit_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'O log operacional de privacidade/integridade é append-only';
END; $$ LANGUAGE plpgsql;

CREATE TRIGGER trg_privacy_integrity_audit_immutable
BEFORE UPDATE OR DELETE ON privacy_integrity_audit
FOR EACH ROW EXECUTE FUNCTION prevent_privacy_integrity_audit_mutation();

GRANT SELECT, INSERT ON privacy_integrity_audit TO app_user;
REVOKE UPDATE, DELETE ON privacy_integrity_audit FROM app_user;
