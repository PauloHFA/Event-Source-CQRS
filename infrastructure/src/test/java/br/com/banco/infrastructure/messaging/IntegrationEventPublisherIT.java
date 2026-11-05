package br.com.banco.infrastructure.messaging;

import br.com.banco.application.port.EventMetadata;
import br.com.banco.domain.Conta;
import br.com.banco.infrastructure.eventstore.EventSerializer;
import br.com.banco.infrastructure.eventstore.JdbcEventStore;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class IntegrationEventPublisherIT {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");

    static HikariDataSource dataSource;
    static JdbcTemplate jdbc;
    static JdbcEventStore eventStore;
    static TransactionTemplate tx;

    @BeforeAll
    static void setup() {
        var config = new HikariConfig();
        config.setJdbcUrl(PG.getJdbcUrl());
        config.setUsername(PG.getUsername());
        config.setPassword(PG.getPassword());
        dataSource = new HikariDataSource(config);
        Flyway.configure().dataSource(dataSource).load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        eventStore = new JdbcEventStore(jdbc, tx, new EventSerializer());
    }

    @AfterAll
    static void tearDown() {
        dataSource.close();
    }

    @Test
    void checkpointSoAvancaAposPublicacaoDoLoteCompleto() {
        UUID accountId = UUID.randomUUID();
        var account = Conta.abrir(accountId, "titular-ref", BigDecimal.ZERO, Clock.systemUTC());
        account.depositar(new BigDecimal("10.00"), "TESTE", Clock.systemUTC());
        var domainEvents = account.eventosPendentes();
        eventStore.append("conta-" + accountId, 0, domainEvents,
                EventMetadata.of(UUID.randomUUID(), "integration-test"));

        List<IntegrationEvent> accepted = new ArrayList<>();
        var failDepositOnce = new boolean[]{true};
        IntegrationEventPublisher publisher = (topic, key, event) -> {
            if (failDepositOnce[0] && event.type().equals("ValorDepositado")) {
                failDepositOnce[0] = false;
                throw new IllegalStateException("Kafka indisponível");
            }
            accepted.add(event);
        };
        var meters = new SimpleMeterRegistry();
        var runner = new IntegrationEventPublisherRunner(jdbc, tx, eventStore,
                new IntegrationEventMapper(), publisher, 10, meters);

        assertThatThrownBy(runner::runOnce).hasMessage("Kafka indisponível");
        assertThat(runner.checkpoint()).isZero();
        assertThat(accepted).extracting(IntegrationEvent::type).containsExactly("ContaAberta");
        assertThat(meters.get("integration.publisher.batches.failed").counter().count()).isEqualTo(1);
        assertThat(meters.get("integration.publisher.failure.event.position").gauge().value()).isEqualTo(2.0);
        assertThat(meters.get("integration.publisher.event.lag").gauge().value()).isEqualTo(2.0);

        assertThat(runner.runOnce()).isEqualTo(2);
        assertThat(runner.checkpoint()).isEqualTo(2);
        assertThat(accepted).extracting(IntegrationEvent::eventId).containsExactly(
                domainEvents.getFirst().eventId(),
                domainEvents.getFirst().eventId(),
                domainEvents.get(1).eventId());
        assertThat(meters.get("integration.publisher.failure.event.position").gauge().value()).isZero();
        assertThat(meters.get("integration.publisher.event.lag").gauge().value()).isZero();

        var restarted = new IntegrationEventPublisherRunner(jdbc, tx, eventStore,
                new IntegrationEventMapper(), publisher, 10, new SimpleMeterRegistry());
        assertThat(restarted.runOnce()).isZero();
        assertThat(restarted.checkpoint()).isEqualTo(2);
        assertThat(accepted).hasSize(3);
    }
}
