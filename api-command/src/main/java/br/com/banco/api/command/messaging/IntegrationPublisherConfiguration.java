package br.com.banco.api.command.messaging;

import br.com.banco.infrastructure.eventstore.JdbcEventStore;
import br.com.banco.infrastructure.messaging.IntegrationEventMapper;
import br.com.banco.infrastructure.messaging.IntegrationEventPublisherRunner;
import br.com.banco.infrastructure.messaging.KafkaIntegrationEventPublisher;
import br.com.banco.infrastructure.messaging.SchemaRegistryClient;
import io.micrometer.core.instrument.MeterRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "banco.messaging.enabled", havingValue = "true")
public class IntegrationPublisherConfiguration {

    @Bean
    SchemaRegistryClient schemaRegistryClient(
            @Value("${banco.messaging.schema-registry-url:http://localhost:8081}") String registryUrl,
            @Value("${banco.messaging.schema-registry-username:}") String username,
            @Value("${banco.messaging.schema-registry-password:}") String password) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        return new SchemaRegistryClient(URI.create(registryUrl), http, new ObjectMapper(), username, password);
    }

    @Bean(destroyMethod = "close")
    KafkaIntegrationEventPublisher integrationEventPublisher(
            SchemaRegistryClient schemaRegistry,
            @Value("${banco.messaging.bootstrap-servers:localhost:9092}") String bootstrapServers,
            @Value("${banco.messaging.client-id:banco-event-publisher}") String clientId) {
        return new KafkaIntegrationEventPublisher(bootstrapServers, clientId, schemaRegistry);
    }

    @Bean
    IntegrationEventMapper integrationEventMapper() {
        return new IntegrationEventMapper();
    }

    @Bean
    IntegrationEventPublisherRunner integrationEventPublisherRunner(
            JdbcTemplate jdbc, TransactionTemplate tx, JdbcEventStore eventStore,
            IntegrationEventMapper mapper, KafkaIntegrationEventPublisher publisher,
            MeterRegistry meters,
            @Value("${banco.messaging.batch-size:100}") int batchSize) {
        return new IntegrationEventPublisherRunner(jdbc, tx, eventStore, mapper, publisher, batchSize, meters);
    }

    @Bean
    IntegrationPublisherScheduler integrationPublisherScheduler(
            IntegrationEventPublisherRunner runner) {
        return new IntegrationPublisherScheduler(runner);
    }

    static class IntegrationPublisherScheduler {
        private final IntegrationEventPublisherRunner runner;

        IntegrationPublisherScheduler(IntegrationEventPublisherRunner runner) {
            this.runner = runner;
        }

        @Scheduled(fixedDelayString = "${banco.messaging.poll-interval:1000}")
        public void publishBatch() {
            runner.runOnce();
        }
    }
}
