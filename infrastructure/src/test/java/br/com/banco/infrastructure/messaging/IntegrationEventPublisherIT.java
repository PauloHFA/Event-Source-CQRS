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
        eventStore.append("conta-" + accountId, 0, account.eventosPendentes(),
                EventMetadata.of(UUID.randomUUID(), "integration-test"));

        List<IntegrationEvent> sent = new ArrayList<>();
        var failOnSecondMessage = new boolean[]{true};
        IntegrationEventPublisher publisher = (topic, key, event) -> {
            sent.add(event);
            if (failOnSecondMessage[0] && sent.size() == 2) {
                throw new IllegalStateException("Kafka indisponível");
            }
        };
        var runner = new IntegrationEventPublisherRunner(jdbc, tx, eventStore,
                new IntegrationEventMapper(), publisher, 10);

        assertThatThrownBy(runner::runOnce).hasMessage("Kafka indisponível");
        assertThat(runner.checkpoint()).isZero();
        assertThat(sent).hasSize(2);

        failOnSecondMessage[0] = false;
        sent.clear();
        assertThat(runner.runOnce()).isEqualTo(2);
        assertThat(runner.checkpoint()).isEqualTo(2);
        assertThat(runner.runOnce()).isZero();
    }
}
