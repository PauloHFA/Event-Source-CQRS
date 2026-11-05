package br.com.banco.infrastructure.messaging;

import br.com.banco.infrastructure.eventstore.JdbcEventStore;
import br.com.banco.infrastructure.eventstore.PublishableEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Timer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

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
    private final MeterRegistry meters;
    private final Counter processedEvents;
    private final Counter failedBatches;
    private final Timer batchDuration;
    private final AtomicLong eventLag = new AtomicLong();
    private final AtomicLong failedEventPosition = new AtomicLong();

    public IntegrationEventPublisherRunner(JdbcTemplate jdbc, TransactionTemplate tx, JdbcEventStore eventStore,
                                           IntegrationEventMapper mapper, IntegrationEventPublisher publisher,
                                           int batchSize) {
        this(jdbc, tx, eventStore, mapper, publisher, batchSize, Metrics.globalRegistry);
    }

    public IntegrationEventPublisherRunner(JdbcTemplate jdbc, TransactionTemplate tx, JdbcEventStore eventStore,
                                           IntegrationEventMapper mapper, IntegrationEventPublisher publisher,
                                           int batchSize, MeterRegistry meters) {
        if (batchSize < 1) throw new IllegalArgumentException("batchSize deve ser positivo.");
        this.jdbc = jdbc;
        this.tx = tx;
        this.eventStore = eventStore;
        this.mapper = mapper;
        this.publisher = publisher;
        this.batchSize = batchSize;
        this.meters = meters;
        this.processedEvents = meters.counter("integration.publisher.events.processed",
                "publisher", CHECKPOINT_NAME);
        this.failedBatches = meters.counter("integration.publisher.batches.failed",
                "publisher", CHECKPOINT_NAME);
        this.batchDuration = meters.timer("integration.publisher.batch.duration",
                "publisher", CHECKPOINT_NAME);
        Gauge.builder("integration.publisher.event.lag", eventLag, AtomicLong::get)
                .tag("publisher", CHECKPOINT_NAME)
                .register(meters);
        Gauge.builder("integration.publisher.failure.event.position", failedEventPosition, AtomicLong::get)
                .tag("publisher", CHECKPOINT_NAME)
                .register(meters);
    }

    /** Processa um lote; retorna a quantidade de eventos de domínio lidos. */
    public int runOnce() {
        Timer.Sample sample = Timer.start(meters);
        try {
            Integer processed;
            try {
                processed = tx.execute(status -> processBatch());
            } catch (RuntimeException e) {
                failedBatches.increment();
                try {
                    updateLag();
                } catch (RuntimeException lagFailure) {
                    if (lagFailure != e) e.addSuppressed(lagFailure);
                }
                throw e;
            }
            int count = Objects.requireNonNull(processed, "A transação do publisher não retornou resultado.");
            processedEvents.increment(count);
            updateLag();
            return count;
        } finally {
            sample.stop(batchDuration);
        }
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
            try {
                mapper.map(event).ifPresent(mapped ->
                        publisher.publish(mapped.topic(), event.streamId(), mapped.event()));
            } catch (RuntimeException e) {
                failedEventPosition.set(event.globalPosition());
                throw e;
            }
        }
        long lastPosition = events.get(events.size() - 1).globalPosition();
        jdbc.update("""
                UPDATE integration_publisher_checkpoints
                SET last_position = ?, updated_at = now()
                WHERE publisher_name = ?
                """, lastPosition, CHECKPOINT_NAME);
        failedEventPosition.set(0);
        return events.size();
    }

    public long checkpoint() {
        List<Long> checkpoints = jdbc.queryForList("""
                SELECT last_position FROM integration_publisher_checkpoints WHERE publisher_name = ?
                """, Long.class, CHECKPOINT_NAME);
        return checkpoints.isEmpty() ? 0 : checkpoints.getFirst();
    }

    private void updateLag() {
        Long latest = jdbc.queryForObject("SELECT COALESCE(MAX(global_position), 0) FROM events", Long.class);
        eventLag.set(Math.max(0, (latest == null ? 0 : latest) - checkpoint()));
    }
}
