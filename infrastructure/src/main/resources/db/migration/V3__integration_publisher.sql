CREATE TABLE integration_publisher_checkpoints (
    publisher_name VARCHAR(100) PRIMARY KEY,
    last_position  BIGINT NOT NULL DEFAULT 0 CHECK (last_position >= 0),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
