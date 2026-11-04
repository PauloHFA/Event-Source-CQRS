package br.com.banco.infrastructure.projections;

import br.com.banco.infrastructure.eventstore.JdbcEventStore;
import br.com.banco.infrastructure.eventstore.StoredEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

/**
 * Lê o log a partir do checkpoint, projeta e avança o checkpoint na mesma transação.
 * O lock da linha de checkpoint garante um único processador por projeção.
 */
public class ProjectionRunner {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final JdbcEventStore store;
    private final ContaProjector projector;
    private final int batchSize;

    public ProjectionRunner(JdbcTemplate jdbc, TransactionTemplate tx, JdbcEventStore store,
                            ContaProjector projector, int batchSize) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.store = store;
        this.projector = projector;
        this.batchSize = batchSize;
    }

    /** Processa um lote. Retorna quantos eventos foram aplicados (0 = em dia). */
    public int runOnce() {
        Integer n = tx.execute(status -> processarLote());
        return n == null ? 0 : n;
    }

    /** Processa até esgotar os eventos visíveis. */
    public long runUntilCaughtUp() {
        long total = 0;
        int n;
        while ((n = runOnce()) > 0) total += n;
        return total;
    }

    /** Reconstrução: esvazia o read model, zera o checkpoint e reprocessa todo o histórico. */
    public long rebuild() {
        tx.executeWithoutResult(status -> {
            garantirCheckpoint();
            jdbc.queryForObject("SELECT last_position FROM projection_checkpoints WHERE projection_name = ? FOR UPDATE",
                    Long.class, ContaProjector.NAME);
            projector.truncate();
            jdbc.update("UPDATE projection_checkpoints SET last_position = 0, updated_at = now() WHERE projection_name = ?",
                    ContaProjector.NAME);
        });
        return runUntilCaughtUp();
    }

    public long checkpoint() {
        garantirCheckpoint();
        Long p = jdbc.queryForObject("SELECT last_position FROM projection_checkpoints WHERE projection_name = ?",
                Long.class, ContaProjector.NAME);
        return p == null ? 0 : p;
    }

    private int processarLote() {
        garantirCheckpoint();
        Long posicao = jdbc.queryForObject(
                "SELECT last_position FROM projection_checkpoints WHERE projection_name = ? FOR UPDATE",
                Long.class, ContaProjector.NAME);
        List<StoredEvent> eventos = store.readAll(posicao == null ? 0 : posicao, batchSize);
        if (eventos.isEmpty()) return 0;
        eventos.forEach(projector::on);
        jdbc.update("UPDATE projection_checkpoints SET last_position = ?, updated_at = now() WHERE projection_name = ?",
                eventos.get(eventos.size() - 1).globalPosition(), ContaProjector.NAME);
        return eventos.size();
    }

    private void garantirCheckpoint() {
        jdbc.update("INSERT INTO projection_checkpoints (projection_name, last_position) VALUES (?, 0) ON CONFLICT DO NOTHING",
                ContaProjector.NAME);
    }
}

