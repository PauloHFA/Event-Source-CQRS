package br.com.banco.infrastructure.privacy;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

public final class JdbcOperationalAuditLog {

    private final JdbcTemplate jdbc;

    public JdbcOperationalAuditLog(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void record(String operation, UUID accountId, String operator, String reason, String outcome,
                       Long verifiedEvents, Long firstInvalidPosition) {
        if (operator == null || operator.isBlank()) {
            throw new IllegalArgumentException("O operador da ação auditada é obrigatório.");
        }
        if (reason == null || reason.isBlank() || reason.length() > 500) {
            throw new IllegalArgumentException("A justificativa deve conter de 1 a 500 caracteres.");
        }
        jdbc.update("""
                        INSERT INTO privacy_integrity_audit
                            (audit_id, operation, account_id, operator_subject, reason, outcome,
                             verified_events, first_invalid_position)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                UUID.randomUUID(), operation, accountId, operator, reason, outcome,
                verifiedEvents, firstInvalidPosition);
    }
}
