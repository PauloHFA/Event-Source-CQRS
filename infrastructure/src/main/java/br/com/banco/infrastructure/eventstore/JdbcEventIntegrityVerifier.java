package br.com.banco.infrastructure.eventstore;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.MessageDigest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class JdbcEventIntegrityVerifier {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate readTransaction;

    public JdbcEventIntegrityVerifier(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.readTransaction = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
        this.readTransaction.setReadOnly(true);
        this.readTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    public IntegrityVerification verify() {
        IntegrityVerification verification = readTransaction.execute(status -> verifyConsistentSnapshot());
        if (verification == null) {
            throw new IllegalStateException("A transação de verificação não retornou resultado.");
        }
        return verification;
    }

    /** Caller must run this method in a repeatable-read transaction. */
    public IntegrityVerification verifyInCurrentTransaction() {
        return verifyConsistentSnapshot();
    }

    private IntegrityVerification verifyConsistentSnapshot() {
        Map<String, StreamState> states = new HashMap<>();
        jdbc.query("""
                SELECT stream_id, chain_start_position, last_position, last_hash
                FROM event_integrity_heads
                """, rs -> {
            while (rs.next()) {
                states.put(rs.getString("stream_id"),
                        new StreamState(rs.getLong("chain_start_position"), rs.getLong("last_position"),
                                rs.getBytes("last_hash")));
            }
            return null;
        });
        if (states.isEmpty()) return new IntegrityVerification(true, 0, 0);

        Map<String, Long> expectedCounts = new HashMap<>();
        jdbc.query("""
                SELECT h.stream_id, count(e.global_position) AS event_count
                FROM event_integrity_heads h
                LEFT JOIN events e ON e.stream_id = h.stream_id
                                 AND e.global_position > h.chain_start_position
                GROUP BY h.stream_id
                """, rs -> {
            while (rs.next()) {
                expectedCounts.put(rs.getString("stream_id"), rs.getLong("event_count"));
            }
            return null;
        });
        List<ChainRow> rows = jdbc.query("""
                SELECT e.global_position, e.event_id, e.stream_id, e.version, e.event_type,
                       e.schema_version, e.payload::text AS payload, e.metadata::text AS metadata,
                       e.occurred_at, c.previous_hash, c.event_hash
                FROM events e
                JOIN event_integrity_chain c ON c.global_position = e.global_position
                JOIN event_integrity_heads h ON h.stream_id = e.stream_id
                WHERE e.global_position > h.chain_start_position
                ORDER BY e.global_position
                """, (rs, row) -> new ChainRow(
                rs.getLong("global_position"), rs.getObject("event_id", UUID.class),
                rs.getString("stream_id"), rs.getInt("version"), rs.getString("event_type"),
                rs.getInt("schema_version"), rs.getString("payload"), rs.getString("metadata"),
                rs.getTimestamp("occurred_at").toInstant(), rs.getBytes("previous_hash"),
                rs.getBytes("event_hash")));

        Map<String, byte[]> previousHashes = new HashMap<>();
        Map<String, Long> verifiedCounts = new HashMap<>();
        Map<String, Long> latestPositions = new HashMap<>();
        for (ChainRow row : rows) {
            byte[] previous = previousHashes.get(row.streamId);
            byte[] calculated = EventIntegrityHash.calculate(previous, row.position, row.eventId, row.streamId,
                    row.version, row.eventType, row.schemaVersion, row.payload, row.metadata, row.occurredAt);
            if (!MessageDigest.isEqual(row.previousHash == null ? new byte[0] : row.previousHash,
                    previous == null ? new byte[0] : previous)
                    || !MessageDigest.isEqual(row.eventHash, calculated)) {
                long verified = verifiedCounts.values().stream().mapToLong(Long::longValue).sum();
                return new IntegrityVerification(false, verified, row.position);
            }
            previousHashes.put(row.streamId, row.eventHash);
            verifiedCounts.merge(row.streamId, 1L, Long::sum);
            latestPositions.put(row.streamId, row.position);
        }
        long verified = verifiedCounts.values().stream().mapToLong(Long::longValue).sum();
        for (Map.Entry<String, StreamState> entry : states.entrySet()) {
            String streamId = entry.getKey();
            StreamState state = entry.getValue();
            if (!verifiedCounts.getOrDefault(streamId, 0L).equals(expectedCounts.getOrDefault(streamId, 0L))) {
                return new IntegrityVerification(false, verified, state.chainStartPosition + 1);
            }
            if (latestPositions.getOrDefault(streamId, state.chainStartPosition) != state.lastPosition
                    || !MessageDigest.isEqual(previousHashes.getOrDefault(streamId, new byte[0]),
                    state.lastHash == null ? new byte[0] : state.lastHash)) {
                return new IntegrityVerification(false, verified, state.lastPosition);
            }
        }
        return new IntegrityVerification(true, verified, 0);
    }

    private record ChainRow(long position, UUID eventId, String streamId, int version, String eventType,
                            int schemaVersion, String payload, String metadata, java.time.Instant occurredAt,
                            byte[] previousHash, byte[] eventHash) {}
    private record StreamState(long chainStartPosition, long lastPosition, byte[] lastHash) {}
}
