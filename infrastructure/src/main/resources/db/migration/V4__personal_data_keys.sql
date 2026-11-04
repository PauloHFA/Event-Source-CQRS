CREATE TABLE personal_data_keys (
    account_id  UUID PRIMARY KEY,
    wrapped_key BYTEA NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

GRANT SELECT, INSERT, DELETE ON personal_data_keys TO app_user;
