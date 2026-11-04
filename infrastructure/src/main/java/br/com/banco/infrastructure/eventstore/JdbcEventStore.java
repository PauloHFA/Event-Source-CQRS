package br.com.banco.infrastructure.eventstore;

import br.com.banco.application.port.ConcurrencyException;
import br.com.banco.application.port.EventMetadata;
import br.com.banco.application.port.EventStore;
import br.com.banco.application.port.EventStream;
import br.com.banco.application.port.TransferenciaStream;
import br.com.banco.domain.TransferenciaEvent;
import br.com.banco.domain.ContaEvent;
import br.com.banco.domain.DomainEvent;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;

/** Event Store append-only em PostgreSQL com concorrência otimista por (stream_id, version). */
public class JdbcEventStore implements EventStore {

    private static final String INSERT_SQL = """
            INSERT INTO events (event_id, stream_id, stream_type, version, event_type,
                                schema_version, payload, metadata, occurred_at)
            VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?)
            """;

    private static final String SELECT_SQL = """
            SELECT version, event_type, payload::text AS payload
            FROM events WHERE stream_id = ? AND version > ? ORDER BY version
            """;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final EventSerializer serializer;

    public JdbcEventStore(JdbcTemplate jdbc, TransactionTemplate tx, EventSerializer serializer) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.serializer = serializer;
    }

    /** Carrega todos os eventos do stream. */
    @Override
    public EventStream load(String streamId) {
        return loadFrom(streamId, 0);
    }

    /** Carrega eventos com versão maior que {@code afterVersion} (base para snapshots). */
    @Override
    public EventStream loadFrom(String streamId, int afterVersion) {
        int[] ultima = {afterVersion};
        List<ContaEvent> eventos = jdbc.query(SELECT_SQL, (rs, i) -> {
            ultima[0] = rs.getInt("version");
            return serializer.deserialize(rs.getString("event_type"), rs.getString("payload"));
        }, streamId, afterVersion);
        return new EventStream(streamId, ultima[0], eventos);
    }

    @Override
    public TransferenciaStream loadTransferencia(String streamId) {
        int[] ultima = {0};
        List<TransferenciaEvent> eventos = jdbc.query(SELECT_SQL, (rs, i) -> {
            ultima[0] = rs.getInt("version");
            return (TransferenciaEvent) serializer.deserializeAny(rs.getString("event_type"), rs.getString("payload"));
        }, streamId, 0);
        return new TransferenciaStream(streamId, ultima[0], eventos);
    }

    private static String streamType(String streamId) {
        return streamId.startsWith("transfer-") ? "Transferencia" : "Conta";
    }

    /** Append atômico: todos os eventos entram ou nenhum. Participa de transação existente. */
    @Override
    public void append(String streamId, int expectedVersion, List<? extends DomainEvent> events,
                       EventMetadata meta) {
        if (events.isEmpty()) return;
        String metaJson = serializer.toJson(meta);
        try {
            tx.executeWithoutResult(status -> {
                int version = expectedVersion;
                for (DomainEvent e : events) {
                    version++;
                    jdbc.update(INSERT_SQL, e.eventId(), streamId, streamType(streamId), version,
                            serializer.typeOf(e), e.schemaVersion(),
                            serializer.toJson(e), metaJson, Timestamp.from(e.em()));
                }
            });
        } catch (DuplicateKeyException ex) {
            throw new ConcurrencyException(streamId, expectedVersion);
        }
    }

    /**
     * Lê o log global a partir de uma posição, para projeções (seção 5.4).
     * Só retorna linhas cujo xmin é anterior ao menor xid ainda ativo, evitando pular eventos de
     * transações que obtiveram a posição mas ainda não fizeram commit.
     * Limitação conhecida: transações que obtêm xid e posição em ordem inversa numa janela de
     * microssegundos ainda podem produzir um buraco; um atraso de segurança pode ser somado se necessário.
     */
    public List<StoredEvent> readAll(long afterPosition, int limit) {
        return jdbc.query("""
                SELECT global_position, stream_id, version, event_type, payload::text AS payload, occurred_at
                FROM events
                WHERE global_position > ?
                  AND stream_type = 'Conta'
                  AND xmin::text::bigint < pg_snapshot_xmin(pg_current_snapshot())::text::bigint
                ORDER BY global_position
                LIMIT ?
                """, (rs, i) -> new StoredEvent(
                rs.getLong("global_position"), rs.getString("stream_id"), rs.getInt("version"),
                serializer.deserialize(rs.getString("event_type"), rs.getString("payload")),
                rs.getTimestamp("occurred_at").toInstant()), afterPosition, limit);
    }

    /**
     * Lê o log global de todos os agregados para o publisher (o Event Store é o próprio outbox).
     * Mesma regra de visibilidade por xmin de {@link #readAll}.
     */
    public List<PublishableEvent> readForPublishing(long afterPosition, int limit) {
        return jdbc.query("""
                SELECT global_position, stream_id, version, event_type, payload::text AS payload,
                       metadata->>'correlationId' AS correlation_id, occurred_at
                FROM events
                WHERE global_position > ?
                  AND xmin::text::bigint < pg_snapshot_xmin(pg_current_snapshot())::text::bigint
                ORDER BY global_position
                LIMIT ?
                """, (rs, i) -> {
            String corr = rs.getString("correlation_id");
            return new PublishableEvent(
                    rs.getLong("global_position"), rs.getString("stream_id"), rs.getInt("version"),
                    serializer.deserializeAny(rs.getString("event_type"), rs.getString("payload")),
                    corr == null ? null : UUID.fromString(corr),
                    rs.getTimestamp("occurred_at").toInstant());
        }, afterPosition, limit);
    }

    public static String contaStreamId(UUID contaId) {
        return "conta-" + contaId;
    }
}
