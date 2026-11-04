package br.com.banco.infrastructure.eventstore;

import br.com.banco.domain.ContaEvent;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EventUpcasterTest {

    private final EventSerializer serializer = new EventSerializer();

    @Test
    void upcastV1DeContaAbertaSemAlterarOEventoPersistido() {
        String legacy = """
                {"eventId":"00000000-0000-0000-0000-000000000001",
                 "contaId":"00000000-0000-0000-0000-000000000002",
                 "titularRef":"titular-legado","limite":0,"em":"2026-01-01T00:00:00Z"}
                """;

        var event = (ContaEvent.ContaAberta) serializer.deserialize(
                "conta-00000000-0000-0000-0000-000000000002", "ContaAberta", 1, legacy);

        assertThat(event.titularRef()).isEqualTo("titular-legado");
        assertThat(EventSerializer.currentSchemaVersion("ContaAberta")).isEqualTo(2);
        assertThat(legacy).doesNotContain("titularRefProtection");
    }

    @Test
    void exigeUpcasterParaCadaVersaoHistorica() {
        assertThatThrownBy(() -> serializer.deserializeAny(null, "ContaAberta", 0, "{}"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Versão de schema inválida");
    }
}
