package br.com.banco.infrastructure.transferencia;

import br.com.banco.application.port.TransferenciaPendentes;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public class JdbcTransferenciaPendentes implements TransferenciaPendentes {

    private static final String PREFIXO = "transfer-";

    private final JdbcTemplate jdbc;

    public JdbcTransferenciaPendentes(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<UUID> pendentesAntesDe(Instant limite) {
        return jdbc.query("""
                SELECT stream_id FROM (
                    SELECT DISTINCT ON (stream_id) stream_id, event_type, occurred_at
                    FROM events WHERE stream_type = 'Transferencia'
                    ORDER BY stream_id, version DESC
                ) ultimo
                WHERE event_type NOT IN ('TransferenciaConcluida', 'TransferenciaRejeitada',
                                         'TransferenciaEstornada', 'CompensacaoFalhou')
                  AND occurred_at < ?
                """, (rs, i) -> UUID.fromString(rs.getString(1).substring(PREFIXO.length())),
                Timestamp.from(limite));
    }
}

