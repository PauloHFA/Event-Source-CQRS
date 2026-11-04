package br.com.banco.application;

import br.com.banco.application.Commands.*;
import br.com.banco.application.ContaCommandHandlerTest.*;
import br.com.banco.application.port.TransferenciaPendentes;
import br.com.banco.domain.*;
import br.com.banco.domain.ContaEvent.*;
import br.com.banco.domain.TransferenciaEvent.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransferenciaSagaTest {

    final Clock clock = Clock.system(ZoneOffset.UTC);
    InMemoryEventStore store;
    ContaCommandHandler contas;
    List<UUID> pendentes = List.of();
    TransferenciaSaga saga;
    final UUID a = UUID.randomUUID();
    final UUID b = UUID.randomUUID();

    private Context ctx() { return new Context(null, UUID.randomUUID(), "teste"); }

    @BeforeEach
    void setup() {
        store = new InMemoryEventStore();
        contas = new ContaCommandHandler(store, new InMemoryIdempotency(), new DirectUow(), clock);
        TransferenciaPendentes query = limite -> pendentes;
        saga = new TransferenciaSaga(store, contas, query, clock);
        contas.handle(new AbrirConta(a, "ta", BigDecimal.ZERO, ctx()));
        contas.handle(new DepositarValor(a, new BigDecimal("200.00"), "X", ctx()));
        contas.handle(new AbrirConta(b, "tb", BigDecimal.ZERO, ctx()));
    }

    private BigDecimal saldo(UUID id) {
        return Conta.reconstruir(store.load("conta-" + id).events()).saldo();
    }

    private SolicitarTransferencia pedido(UUID transferId, String valor) {
        return new SolicitarTransferencia(transferId, a, b, new BigDecimal(valor), ctx());
    }

    @Test
    void CA10_transferenciaComSucessoMantemTotalConstante() {
        var r = saga.solicitar(pedido(UUID.randomUUID(), "80.00"));

        assertThat(r.status()).isEqualTo(Transferencia.Status.CONCLUIDA);
        assertThat(saldo(a)).isEqualByComparingTo("120.00");
        assertThat(saldo(b)).isEqualByComparingTo("80.00");
        assertThat(saldo(a).add(saldo(b))).isEqualByComparingTo("200.00");
    }

    @Test
    void CA11_destinoEncerradoGeraEstornoCompensatorio() {
        contas.handle(new EncerrarConta(b, ctx()));

        var r = saga.solicitar(pedido(UUID.randomUUID(), "80.00"));

        assertThat(r.status()).isEqualTo(Transferencia.Status.ESTORNADA);
        assertThat(saldo(a)).isEqualByComparingTo("200.00");
        assertThat(saldo(b)).isEqualByComparingTo("0");
        // histórico mantém saque e estorno visíveis (nada é removido)
        var tipos = store.load("conta-" + a).events().stream().map(e -> e.getClass().getSimpleName()).toList();
        assertThat(tipos).containsSubsequence("ValorDepositado", "ValorSacado", "ValorEstornado");
    }

    @Test
    void saldoInsuficienteRejeitaSemMovimentarNada() {
        var r = saga.solicitar(pedido(UUID.randomUUID(), "500.00"));

        assertThat(r.status()).isEqualTo(Transferencia.Status.REJEITADA);
        assertThat(saldo(a)).isEqualByComparingTo("200.00");
        assertThat(saldo(b)).isEqualByComparingTo("0");
        assertThat(store.load("conta-" + a).events()).noneMatch(e -> e instanceof ValorSacado);
    }

    @Test
    void RN06_mesmaContaEhProibida() {
        assertThatThrownBy(() -> saga.solicitar(new SolicitarTransferencia(UUID.randomUUID(), a, a,
                BigDecimal.TEN, ctx()))).isInstanceOf(TransferenciaMesmaContaException.class);
    }

    @Test
    void solicitarDuasVezesComOMesmoIdNaoDuplicaEfeitos() {
        var id = UUID.randomUUID();

        saga.solicitar(pedido(id, "80.00"));
        var segunda = saga.solicitar(pedido(id, "80.00"));

        assertThat(segunda.status()).isEqualTo(Transferencia.Status.CONCLUIDA);
        assertThat(saldo(a)).isEqualByComparingTo("120.00");
        assertThat(saldo(b)).isEqualByComparingTo("80.00");
    }

    @Test
    void sagaTravadaAposODebitoEhRetomadaSemDebitarDeNovo() {
        var id = UUID.randomUUID();
        // simula queda: saque já executado (com a chave do passo) e saga parada em ORIGEM_DEBITADA
        contas.handle(new SacarValor(a, new BigDecimal("80.00"),
                new Context("transfer-" + id + "-saque", id, "saga")));
        var t = Transferencia.solicitar(id, a, b, new BigDecimal("80.00"), clock);
        t.registrarOrigemDebitada(clock);
        store.append("transfer-" + id, 0, t.eventosPendentes(), br.com.banco.application.port.EventMetadata.of(id, "saga"));
        pendentes = List.of(id);

        int retomadas = saga.recuperarTravadas(Duration.ofMinutes(5));

        assertThat(retomadas).isEqualTo(1);
        assertThat(saga.consultar(id).status()).isEqualTo(Transferencia.Status.CONCLUIDA);
        assertThat(saldo(a)).isEqualByComparingTo("120.00"); // débito não foi repetido
        assertThat(saldo(b)).isEqualByComparingTo("80.00");
    }

    @Test
    void sagaTravadaEmEstornoEhConcluidaNaRecuperacao() {
        var id = UUID.randomUUID();
        contas.handle(new SacarValor(a, new BigDecimal("80.00"),
                new Context("transfer-" + id + "-saque", id, "saga")));
        var t = Transferencia.solicitar(id, a, b, new BigDecimal("80.00"), clock);
        t.registrarOrigemDebitada(clock);
        t.falharCreditoDestino("destino encerrado", clock);
        store.append("transfer-" + id, 0, t.eventosPendentes(), br.com.banco.application.port.EventMetadata.of(id, "saga"));

        var r = saga.avancar(id, "saga");

        assertThat(r.status()).isEqualTo(Transferencia.Status.ESTORNADA);
        assertThat(saldo(a)).isEqualByComparingTo("200.00");
    }
}

