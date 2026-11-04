package br.com.banco.infrastructure.messaging;

import br.com.banco.infrastructure.eventstore.JdbcEventStore;
import br.com.banco.infrastructure.eventstore.PublishableEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Objects;

/**
 * Publica eventos do Event Store em ordem global e avança o checkpoint somente após o ack do Kafka.
 * A transação também serializa execuções concorrentes deste publisher entre instâncias da aplicação.
 */
public final class IntegrationEventPublisherRunner {

    public static final String CHECKPOINT_NAME = "integration-events-v1";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final JdbcEventStore eventStore;
    private final IntegrationEventMapper mapper;
    private final IntegrationEventPublisher publisher;
    private final int batchSize;

    public IntegrationEventPublisherRunner(JdbcTemplate jdbc, TransactionTemplate tx, JdbcEventStore eventStore,
                                           IntegrationEventMapper mapper, IntegrationEventPublisher publisher,
                                           int batchSize) {
        if (batchSize < 1) throw new IllegalArgumentException("batchSize deve ser positivo.");
        this.jdbc = jdbc;
        this.tx = tx;
        this.eventStore = eventStore;
        this.mapper = mapper;
        this.publisher = publisher;
        this.batchSize = batchSize;
    }

    /** Processa um lote; retorna a quantidade de eventos de domínio lidos. */
    public int runOnce() {
        Integer processed = tx.execute(status -> processBatch());
        return Objects.requireNonNull(processed, "A transação do publisher não retornou resultado.");
    }

    private int processBatch() {
        jdbc.update("""
                INSERT INTO integration_publisher_checkpoints (publisher_name, last_position)
                VALUES (?, 0) ON CONFLICT (publisher_name) DO NOTHING
                """, CHECKPOINT_NAME);
        Long checkpoint = jdbc.queryForObject("""
                SELECT last_position FROM integration_publisher_checkpoints
                WHERE publisher_name = ? FOR UPDATE
                """, Long.class, CHECKPOINT_NAME);
        long position = Objects.requireNonNull(checkpoint, "Checkpoint do publisher não encontrado.");
        List<PublishableEvent> events = eventStore.readForPublishing(position, batchSize);
        if (events.isEmpty()) return 0;

        for (PublishableEvent event : events) {
            mapper.map(event).ifPresent(mapped ->
                    publisher.publish(mapped.topic(), event.streamId(), mapped.event()));
        }
        long lastPosition = events.get(events.size() - 1).globalPosition();
        jdbc.update("""
                UPDATE integration_publisher_checkpoints
                SET last_position = ?, updated_at = now()
                WHERE publisher_name = ?
                """, lastPosition, CHECKPOINT_NAME);
        return events.size();
    }

    public long checkpoint() {
        List<Long> checkpoints = jdbc.queryForList("""
                SELECT last_position FROM integration_publisher_checkpoints WHERE publisher_name = ?
                """, Long.class, CHECKPOINT_NAME);
        return checkpoints.isEmpty() ? 0 : checkpoints.getFirst();
    }
}
