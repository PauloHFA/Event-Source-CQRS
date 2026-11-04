CREATE TABLE event_integrity_heads (
    stream_id            VARCHAR(100) PRIMARY KEY,
    chain_start_position BIGINT NOT NULL,
    last_position        BIGINT NOT NULL,
    last_hash            BYTEA
);

INSERT INTO event_integrity_heads (stream_id, chain_start_position, last_position, last_hash)
SELECT stream_id, MAX(global_position), MAX(global_position), NULL
FROM events
GROUP BY stream_id;

CREATE TABLE event_integrity_chain (
    global_position BIGINT PRIMARY KEY REFERENCES events(global_position),
    event_id        UUID NOT NULL UNIQUE REFERENCES events(event_id),
    previous_hash   BYTEA,
    event_hash      BYTEA NOT NULL UNIQUE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE OR REPLACE FUNCTION prevent_integrity_chain_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'A cadeia de integridade é append-only';
END; $$ LANGUAGE plpgsql;

CREATE TRIGGER trg_integrity_chain_immutable
BEFORE UPDATE OR DELETE ON event_integrity_chain
FOR EACH ROW EXECUTE FUNCTION prevent_integrity_chain_mutation();

GRANT SELECT, INSERT ON event_integrity_chain TO app_user;
GRANT SELECT, INSERT, UPDATE ON event_integrity_heads TO app_user;
