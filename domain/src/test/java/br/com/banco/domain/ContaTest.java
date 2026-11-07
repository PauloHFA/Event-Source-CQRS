package br.com.banco.domain;

import br.com.banco.domain.ContaEvent.*;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ContaTest {

    private static final Instant T = Instant.parse("2026-01-01T10:00:00Z");
    private final Clock clock = Clock.fixed(T, ZoneOffset.UTC);
    private final UUID id = UUID.randomUUID();

    private static BigDecimal bd(String v) { return new BigDecimal(v); }

    private ContaEvent aberta(String limite) {
        return new ContaAberta(UUID.randomUUID(), id, "titular-1", bd(limite), T);
    }

    private ContaEvent depositado(String valor) {
        return new ValorDepositado(UUID.randomUUID(), id, bd(valor), "TESTE", T);
    }

    @Test
    void CA01_depositoGeraEventoESaldoReconstruido() {
        var conta = Conta.reconstruir(List.of(aberta("0"), depositado("100.00")));
        int versaoAntes = conta.versao();

        conta.depositar(bd("50.00"), "CAIXA", clock);

        assertThat(conta.eventosPendentes()).hasSize(1).first().isInstanceOf(ValorDepositado.class);
        assertThat(conta.saldo()).isEqualByComparingTo("150.00");
        assertThat(conta.versao()).isEqualTo(versaoAntes + 1);
        assertThat(conta.versaoPersistida()).isEqualTo(versaoAntes);
    }

    @Test
    void CA02_saqueAcimaDoSaldoEhRejeitadoSemEventos() {
        var conta = Conta.reconstruir(List.of(aberta("0"), depositado("100")));

        assertThatThrownBy(() -> conta.sacar(bd("150"), clock))
                .isInstanceOf(SaldoInsuficienteException.class);
        assertThat(conta.eventosPendentes()).isEmpty();
    }

    @Test
    void saqueUsaLimiteDeCredito() {
        var conta = Conta.reconstruir(List.of(aberta("200"), depositado("100")));

        conta.sacar(bd("300"), clock);

        assertThat(conta.saldo()).isEqualByComparingTo("-200");
        assertThatThrownBy(() -> conta.sacar(bd("0.01"), clock))
                .isInstanceOf(SaldoInsuficienteException.class);
    }

    @Test
    void RN07_limiteDiarioConfiguradoRejeitaDebitosQueExcedemOTetoDoDia() {
        var conta = Conta.reconstruir(List.of(aberta("0"), depositado("1000")));
        conta.configurarLimiteDiario(bd("300.00"), clock);

        // Primeiro saque de 200: permitido
        conta.sacar(bd("200.00"), clock);
        assertThat(conta.saldo()).isEqualByComparingTo("800.00");

        // Segundo saque de 150 no mesmo dia: ultrapassaria 300 (200 + 150 = 350)
        assertThatThrownBy(() -> conta.sacar(bd("150.00"), clock))
                .isInstanceOf(LimiteDiarioExcedidoException.class);

        // Saque de 100 restante: permitido
        conta.sacar(bd("100.00"), clock);
        assertThat(conta.saldo()).isEqualByComparingTo("700.00");

        // No dia seguinte, limite é renovado
        Clock clockDiaSeguinte = Clock.fixed(T.plusSeconds(86400), ZoneOffset.UTC);
        conta.sacar(bd("250.00"), clockDiaSeguinte);
        assertThat(conta.saldo()).isEqualByComparingTo("450.00");
    }

    @Test
    void RN07_estornoAbateDoTotalDebitadoNoDia() {
        var conta = Conta.reconstruir(List.of(aberta("0"), depositado("1000")));
        conta.configurarLimiteDiario(bd("300.00"), clock);

        conta.sacar(bd("300.00"), clock);

        // Estorno da transferência de 100 no mesmo dia
        conta.estornar(bd("100.00"), UUID.randomUUID(), clock);

        // Agora pode sacar mais 100
        conta.sacar(bd("100.00"), clock);
        assertThat(conta.saldo()).isEqualByComparingTo("700.00");
    }

    @Test
    void valoresNaoPositivosSaoRejeitados() {
        var conta = Conta.reconstruir(List.of(aberta("0"), depositado("100")));

        assertThatThrownBy(() -> conta.depositar(bd("0"), "X", clock)).isInstanceOf(ValorInvalidoException.class);
        assertThatThrownBy(() -> conta.sacar(bd("-1"), clock)).isInstanceOf(ValorInvalidoException.class);
        assertThatThrownBy(() -> conta.depositar(null, "X", clock)).isInstanceOf(ValorInvalidoException.class);
        assertThatThrownBy(() -> conta.configurarLimiteDiario(bd("-50"), clock)).isInstanceOf(ValorInvalidoException.class);
        assertThat(conta.eventosPendentes()).isEmpty();
    }

    @Test
    void contaBloqueadaRejeitaDebitoMasAceitaCredito() {
        var conta = Conta.reconstruir(List.of(aberta("0"), depositado("100")));
        conta.bloquear("fraude", clock);

        assertThatThrownBy(() -> conta.sacar(bd("10"), clock)).isInstanceOf(ContaBloqueadaException.class);
        conta.depositar(bd("10"), "X", clock);

        conta.desbloquear(clock);
        conta.sacar(bd("10"), clock);
        assertThat(conta.saldo()).isEqualByComparingTo("100");
    }

    @Test
    void encerrarExigeSaldoZeroENadaMaisEhAceito() {
        var conta = Conta.reconstruir(List.of(aberta("0"), depositado("100")));

        assertThatThrownBy(() -> conta.encerrar(clock)).isInstanceOf(SaldoNaoZeradoException.class);

        conta.sacar(bd("100"), clock);
        conta.encerrar(clock);

        assertThat(conta.status()).isEqualTo(Conta.Status.ENCERRADA);
        assertThatThrownBy(() -> conta.depositar(bd("1"), "X", clock)).isInstanceOf(ContaInvalidaException.class);
        assertThatThrownBy(() -> conta.sacar(bd("1"), clock)).isInstanceOf(ContaInvalidaException.class);
        assertThatThrownBy(() -> conta.bloquear("x", clock)).isInstanceOf(ContaInvalidaException.class);
    }

    @Test
    void abrirContaGeraContaAberta() {
        var conta = Conta.abrir(id, "titular-1", bd("500"), clock);

        assertThat(conta.eventosPendentes()).singleElement().isInstanceOf(ContaAberta.class);
        assertThat(conta.status()).isEqualTo(Conta.Status.ATIVA);
        assertThat(conta.versaoPersistida()).isZero();
        assertThatThrownBy(() -> Conta.abrir(id, "t", bd("-1"), clock)).isInstanceOf(ValorInvalidoException.class);
    }

    @Test
    void CA06_reconstrucaoEhDeterministica() {
        var historico = List.of(aberta("100"), depositado("80"),
                (ContaEvent) new ValorSacado(UUID.randomUUID(), id, bd("30"), T));

        var a = Conta.reconstruir(historico);
        var b = Conta.reconstruir(historico);

        assertThat(a).usingRecursiveComparison().isEqualTo(b);
        assertThat(a.eventosPendentes()).isEmpty();
        assertThat(a.saldo()).isEqualByComparingTo("50");
        assertThat(a.versao()).isEqualTo(3);
    }

    @Test
    void comandoEmContaNovaEhRejeitado() {
        var conta = Conta.reconstruir(List.of());
        assertThatThrownBy(() -> conta.depositar(bd("1"), "X", clock)).isInstanceOf(ContaInvalidaException.class);
    }
}
