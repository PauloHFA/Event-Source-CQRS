package br.com.banco.infrastructure.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class KafkaIntegrationEventPublisherTest {

    @Test
    void configuraRetentativasIdempotentesComPrazoMaximoDeEntrega() {
        Properties properties = KafkaIntegrationEventPublisher.producerProperties("kafka:9092", "publisher-test");

        assertThat(properties.get(ProducerConfig.ACKS_CONFIG)).isEqualTo("all");
        assertThat(properties.get(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG)).isEqualTo(true);
        assertThat(properties.get(ProducerConfig.RETRIES_CONFIG)).isEqualTo(Integer.MAX_VALUE);
        assertThat(properties.get(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG)).isEqualTo(30_000);
        assertThat(properties.get(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG)).isEqualTo(120_000);
        assertThat(properties.get(ProducerConfig.RETRY_BACKOFF_MS_CONFIG)).isEqualTo(100);
    }

    @Test
    void codificaIdDoSchemaNoFormatoWireConfluent() throws Exception {
        UUID eventId = UUID.randomUUID();
        var event = new IntegrationEvent(eventId, "ValorDepositado", "conta-1", 2,
                Instant.parse("2026-01-01T00:00:00Z"), null, Map.of("valor", "10.00"));

        byte[] encoded = KafkaIntegrationEventPublisher.encode(258, event);

        ByteBuffer header = ByteBuffer.wrap(encoded);
        assertThat(header.get()).isZero();
        assertThat(header.getInt()).isEqualTo(258);
        byte[] payload = new byte[header.remaining()];
        header.get(payload);
        JsonNode json = new ObjectMapper().readTree(payload);
        assertThat(json.path("eventId").asText()).isEqualTo(eventId.toString());
        assertThat(json.path("occurredAt").asText()).isEqualTo("2026-01-01T00:00:00Z");
    }
}
