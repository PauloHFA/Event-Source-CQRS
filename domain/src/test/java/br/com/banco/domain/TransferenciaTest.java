package br.com.banco.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransferenciaTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-01-01T10:00:00Z"), ZoneOffset.UTC);
    private final UUID id = UUID.randomUUID();
    private final UUID a = UUID.randomUUID();
    private final UUID b = UUID.randomUUID();

    private Transferencia solicitada() {
        var t = Transferencia.solicitar(id, a, b, new BigDecimal("80"), clock);
        return Transferencia.reconstruir(t.eventosPendentes());
    }

    @Test
    void caminhoFelizTerminaConcluida() {
        var t = solicitada();
        t.registrarOrigemDebitada(clock);
        t.concluir(clock);

        assertThat(t.status()).isEqualTo(Transferencia.Status.CONCLUIDA);
        assertThat(t.terminal()).isTrue();
        assertThat(t.eventosPendentes()).hasSize(2);
    }

    @Test
    void falhaNoDestinoLevaAoEstorno() {
        var t = solicitada();
        t.registrarOrigemDebitada(clock);
        t.falharCreditoDestino("destino encerrado", clock);
        assertThat(t.status()).isEqualTo(Transferencia.Status.ESTORNANDO);
        assertThat(t.terminal()).isFalse();

        t.concluirEstorno(clock);
        assertThat(t.status()).isEqualTo(Transferencia.Status.ESTORNADA);
        assertThat(t.terminal()).isTrue();
    }

    @Test
    void RN06_transferenciaParaMesmaContaEhProibida() {
        assertThatThrownBy(() -> Transferencia.solicitar(id, a, a, BigDecimal.TEN, clock))
                .isInstanceOf(TransferenciaMesmaContaException.class);
    }

    @Test
    void valorDeveSerPositivo() {
        assertThatThrownBy(() -> Transferencia.solicitar(id, a, b, BigDecimal.ZERO, clock))
                .isInstanceOf(ValorInvalidoException.class);
    }

    @Test
    void transicoesForaDeOrdemSaoRejeitadas() {
        var t = solicitada();
        assertThatThrownBy(() -> t.concluir(clock)).isInstanceOf(TransferenciaEstadoInvalidaException.class);
        assertThatThrownBy(() -> t.concluirEstorno(clock)).isInstanceOf(TransferenciaEstadoInvalidaException.class);

        t.rejeitar("sem saldo", clock);
        assertThatThrownBy(() -> t.registrarOrigemDebitada(clock))
                .isInstanceOf(TransferenciaEstadoInvalidaException.class);
        assertThat(t.terminal()).isTrue();
    }

    @Test
    void estornoDevolveOValorACondicaoDeContaOperavel() {
        var conta = Conta.reconstruir(List.of(
                new ContaEvent.ContaAberta(UUID.randomUUID(), a, "t", BigDecimal.ZERO, clock.instant()),
                new ContaEvent.ValorDepositado(UUID.randomUUID(), a, new BigDecimal("200"), "X", clock.instant()),
                new ContaEvent.ValorSacado(UUID.randomUUID(), a, new BigDecimal("80"), clock.instant())));

        conta.estornar(new BigDecimal("80"), id, clock);

        assertThat(conta.saldo()).isEqualByComparingTo("200");
        assertThat(conta.eventosPendentes()).singleElement().isInstanceOf(ContaEvent.ValorEstornado.class);
        conta.bloquear("x", clock);
        conta.estornar(BigDecimal.ONE, id, clock); // crédito permitido com conta bloqueada
        assertThat(conta.saldo()).isEqualByComparingTo("201");
    }
}

