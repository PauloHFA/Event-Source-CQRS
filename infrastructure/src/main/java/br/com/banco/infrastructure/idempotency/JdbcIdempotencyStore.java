package br.com.banco.infrastructure.idempotency;

import br.com.banco.application.CommandResult;
import br.com.banco.application.port.ConcurrencyException;
import br.com.banco.application.port.IdempotencyStore;
import br.com.banco.infrastructure.eventstore.EventSerializer;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Optional;

public class JdbcIdempotencyStore implements IdempotencyStore {

    private final JdbcTemplate jdbc;
    private final EventSerializer serializer;

    public JdbcIdempotencyStore(JdbcTemplate jdbc, EventSerializer serializer) {
        this.jdbc = jdbc;
        this.serializer = serializer;
    }

    @Override
    public Optional<CommandResult> find(String key) {
        return jdbc.query("SELECT result::text FROM idempotency_keys WHERE key = ?",
                        (rs, i) -> serializer.fromJson(rs.getString(1), CommandResult.class), key)
                .stream().findFirst();
    }

    @Override
    public void save(String key, CommandResult result) {
        try {
            jdbc.update("INSERT INTO idempotency_keys (key, result) VALUES (?, ?::jsonb)",
                    key, serializer.toJson(result));
        } catch (DuplicateKeyException e) {
            // Corrida com outra requisição de mesma chave: a transação inteira é revertida e refeita.
            throw new ConcurrencyException("idempotency:" + key, 0);
        }
    }
}

