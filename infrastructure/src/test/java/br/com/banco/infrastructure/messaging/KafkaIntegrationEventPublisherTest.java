package br.com.banco.infrastructure.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class KafkaIntegrationEventPublisherTest {

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
