CREATE TABLE rm_conta_saldo (
    conta_id      UUID PRIMARY KEY,
    saldo         NUMERIC(18,2) NOT NULL,
    limite        NUMERIC(18,2) NOT NULL,
    status        VARCHAR(20)   NOT NULL,
    versao        INT           NOT NULL,
    atualizado_em TIMESTAMPTZ   NOT NULL
);

CREATE TABLE rm_extrato (
    id          BIGSERIAL PRIMARY KEY,
    conta_id    UUID          NOT NULL,
    event_id    UUID          NOT NULL UNIQUE,
    tipo        VARCHAR(30)   NOT NULL,
    valor       NUMERIC(18,2) NOT NULL,
    saldo_apos  NUMERIC(18,2) NOT NULL,
    ocorrido_em TIMESTAMPTZ   NOT NULL
);
CREATE INDEX ix_extrato_conta_data ON rm_extrato (conta_id, ocorrido_em DESC);

