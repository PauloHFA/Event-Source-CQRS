package br.com.banco.api.command.transferencia;

import br.com.banco.application.TransferenciaSaga;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TransferenciaRecoveryConfigurationTest {

    @Test
    void agendadorInvocaRecuperacaoEIncrementaMetrica() {
        TransferenciaSaga saga = mock(TransferenciaSaga.class);
        when(saga.recuperarTravadas(any(Duration.class))).thenReturn(3);

        var registry = new SimpleMeterRegistry();
        var scheduler = new TransferenciaRecoveryConfiguration.TransferenciaRecoveryScheduler(
                saga, registry, Duration.ofSeconds(120));

        int recuperadas = scheduler.recoverStuckTransfers();

        assertThat(recuperadas).isEqualTo(3);
        verify(saga).recuperarTravadas(Duration.ofSeconds(120));
        assertThat(registry.get("transferencia.saga.recovered.total").counter().count()).isEqualTo(3.0);
    }

    @Test
    void agendadorTrataExcecaoComResiliencia() {
        TransferenciaSaga saga = mock(TransferenciaSaga.class);
        when(saga.recuperarTravadas(any(Duration.class))).thenThrow(new RuntimeException("database timeout"));

        var registry = new SimpleMeterRegistry();
        var scheduler = new TransferenciaRecoveryConfiguration.TransferenciaRecoveryScheduler(
                saga, registry, Duration.ofSeconds(120));

        int recuperadas = scheduler.recoverStuckTransfers();

        assertThat(recuperadas).isEqualTo(0);
    }
}
