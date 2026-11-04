CREATE TABLE events (
    global_position  BIGSERIAL PRIMARY KEY,
    event_id         UUID         NOT NULL UNIQUE,
    stream_id        VARCHAR(100) NOT NULL,
    stream_type      VARCHAR(50)  NOT NULL,
    version          INT          NOT NULL,
    event_type       VARCHAR(150) NOT NULL,
    schema_version   INT          NOT NULL DEFAULT 1,
    payload          JSONB        NOT NULL,
    metadata         JSONB        NOT NULL,
    occurred_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_stream_version UNIQUE (stream_id, version)
);
CREATE INDEX ix_events_stream ON events (stream_id, version);

CREATE TABLE snapshots (
    stream_id   VARCHAR(100) PRIMARY KEY,
    version     INT          NOT NULL,
    state       JSONB        NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE projection_checkpoints (
    projection_name  VARCHAR(100) PRIMARY KEY,
    last_position    BIGINT NOT NULL DEFAULT 0,
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE idempotency_keys (
    key         VARCHAR(100) PRIMARY KEY,
    result      JSONB,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Imutabilidade imposta pelo banco (RNF-01)
CREATE OR REPLACE FUNCTION prevent_event_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'A tabela events é append-only';
END; $$ LANGUAGE plpgsql;

CREATE TRIGGER trg_events_immutable
BEFORE UPDATE OR DELETE ON events
FOR EACH ROW EXECUTE FUNCTION prevent_event_mutation();

-- Usuário da aplicação: apenas INSERT e SELECT em events
DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'app_user') THEN
        CREATE ROLE app_user NOLOGIN;
    END IF;
END $$;

GRANT SELECT, INSERT ON events TO app_user;
GRANT USAGE, SELECT ON SEQUENCE events_global_position_seq TO app_user;
REVOKE UPDATE, DELETE ON events FROM app_user;

