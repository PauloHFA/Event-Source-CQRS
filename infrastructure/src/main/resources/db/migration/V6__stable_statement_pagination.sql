DROP INDEX ix_extrato_conta_data;
CREATE INDEX ix_extrato_conta_data
    ON rm_extrato (conta_id, ocorrido_em DESC, id DESC);
