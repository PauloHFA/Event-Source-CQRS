package br.com.banco.infrastructure.messaging;

import br.com.banco.domain.ContaEvent;
import br.com.banco.domain.TransferenciaEvent;
import br.com.banco.infrastructure.eventstore.PublishableEvent;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class IntegrationEventMapperTest {

    private final IntegrationEventMapper mapper = new IntegrationEventMapper();

    @Test
    void publicaContratoEnxutoSemExporReferenciaDoTitular() {
        UUID eventId = UUID.randomUUID();
        var domainEvent = new ContaEvent.ContaAberta(eventId, UUID.randomUUID(), "titular-ref",
                new BigDecimal("25.00"), Instant.parse("2026-01-01T00:00:00Z"));
        var published = new PublishableEvent(1, "conta-" + UUID.randomUUID(), 1, domainEvent,
                UUID.randomUUID(), domainEvent.em());

        var mapped = mapper.map(published).orElseThrow();

        assertThat(mapped.topic()).isEqualTo(IntegrationEventMapper.TOPIC_CONTA);
        assertThat(mapped.event().eventId()).isEqualTo(eventId);
        assertThat(mapped.event().type()).isEqualTo("ContaAberta");
        assertThat(mapped.event().data()).containsEntry("limite", "25.00")
                .doesNotContainKey("titularRef");
    }

    @Test
    void naoPublicaPassoInternoDaSaga() {
        var domainEvent = new TransferenciaEvent.OrigemDebitada(UUID.randomUUID(), UUID.randomUUID(),
                Instant.parse("2026-01-01T00:00:00Z"));
        var published = new PublishableEvent(1, "transfer-" + UUID.randomUUID(), 2, domainEvent,
                null, domainEvent.em());

        assertThat(mapper.map(published)).isEmpty();
    }
}
