package br.com.banco.infrastructure.projections;

import br.com.banco.domain.ContaEvent.*;
import br.com.banco.infrastructure.eventstore.StoredEvent;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.UUID;

/**
 * Projeta saldo (rm_conta_saldo) e extrato (rm_extrato). Idempotente: o saldo só avança quando a
 * versão do stream é maior que a já aplicada, e o extrato é único por event_id.
 * Deve ser chamado dentro de uma transação, junto com o avanço do checkpoint.
 */
public class ContaProjector {

    public static final String NAME = "conta-read-model";

    private final JdbcTemplate jdbc;

    public ContaProjector(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void on(StoredEvent stored) {
        int v = stored.version();
        switch (stored.event()) {
            case ContaAberta e -> jdbc.update("""
                    INSERT INTO rm_conta_saldo (conta_id, saldo, limite, status, versao, atualizado_em)
                    VALUES (?, 0, ?, 'ATIVA', ?, ?) ON CONFLICT (conta_id) DO NOTHING
                    """, e.contaId(), e.limite(), v, Timestamp.from(e.em()));
            case ValorDepositado e -> movimentar(e.contaId(), v, e.valor(), "DEPOSITO", e.eventId(), stored);
            case ValorSacado e -> movimentar(e.contaId(), v, e.valor().negate(), "SAQUE", e.eventId(), stored);
            case ValorEstornado e -> movimentar(e.contaId(), v, e.valor(), "ESTORNO", e.eventId(), stored);
            case LimiteDiarioConfigurado e -> limiteDiario(e.contaId(), v, e.novoLimite(), stored);
            case ContaBloqueada e -> status(e.contaId(), v, "BLOQUEADA", stored);
            case ContaDesbloqueada e -> status(e.contaId(), v, "ATIVA", stored);
            case ContaEncerrada e -> status(e.contaId(), v, "ENCERRADA", stored);
        }
    }

    private void movimentar(UUID contaId, int versao, BigDecimal delta, String tipo, UUID eventId,
                            StoredEvent stored) {
        int atualizadas = jdbc.update("""
                UPDATE rm_conta_saldo SET saldo = saldo + ?, versao = ?, atualizado_em = ?
                WHERE conta_id = ? AND versao < ?
                """, delta, versao, Timestamp.from(stored.occurredAt()), contaId, versao);
        if (atualizadas > 0) {
            jdbc.update("""
                    INSERT INTO rm_extrato (conta_id, event_id, tipo, valor, saldo_apos, ocorrido_em)
                    SELECT conta_id, ?, ?, ?, saldo, ? FROM rm_conta_saldo WHERE conta_id = ?
                    ON CONFLICT (event_id) DO NOTHING
                    """, eventId, tipo, delta.abs(), Timestamp.from(stored.occurredAt()), contaId);
        }
    }

    private void status(UUID contaId, int versao, String status, StoredEvent stored) {
        jdbc.update("""
                UPDATE rm_conta_saldo SET status = ?, versao = ?, atualizado_em = ?
                WHERE conta_id = ? AND versao < ?
                """, status, versao, Timestamp.from(stored.occurredAt()), contaId, versao);
    }

    private void limiteDiario(UUID contaId, int versao, BigDecimal limiteDiario, StoredEvent stored) {
        jdbc.update("""
                UPDATE rm_conta_saldo SET limite_diario = ?, versao = ?, atualizado_em = ?
                WHERE conta_id = ? AND versao < ?
                """, limiteDiario, versao, Timestamp.from(stored.occurredAt()), contaId, versao);
    }

    /** Esvazia o read model (a reconstrução zera também o checkpoint). */
    public void truncate() {
        jdbc.update("TRUNCATE rm_extrato, rm_conta_saldo");
    }
}

