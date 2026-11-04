package br.com.banco.infrastructure.snapshots;

import br.com.banco.application.port.SnapshotStore;
import br.com.banco.domain.ContaSnapshot;
import br.com.banco.infrastructure.eventstore.EventSerializer;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Optional;

public class JdbcSnapshotStore implements SnapshotStore {

    private final JdbcTemplate jdbc;
    private final EventSerializer serializer;

    public JdbcSnapshotStore(JdbcTemplate jdbc, EventSerializer serializer) {
        this.jdbc = jdbc;
        this.serializer = serializer;
    }

    /** Snapshot ilegível ou de esquema diferente é descartado: o replay completo o substitui. */
    @Override
    public Optional<ContaSnapshot> find(String streamId) {
        return jdbc.query("SELECT state::text FROM snapshots WHERE stream_id = ?",
                        (rs, i) -> rs.getString(1), streamId)
                .stream().findFirst()
                .flatMap(json -> {
                    try {
                        return Optional.of(serializer.fromJson(json, ContaSnapshot.class));
                    } catch (RuntimeException e) {
                        return Optional.empty();
                    }
                })
                .filter(ContaSnapshot::compativel);
    }

    @Override
    public void save(String streamId, ContaSnapshot s) {
        jdbc.update("""
                INSERT INTO snapshots (stream_id, version, state) VALUES (?, ?, ?::jsonb)
                ON CONFLICT (stream_id) DO UPDATE
                SET version = EXCLUDED.version, state = EXCLUDED.state, created_at = now()
                WHERE snapshots.version < EXCLUDED.version
                """, streamId, s.versao(), serializer.toJson(s));
    }
}

