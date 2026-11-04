package br.com.banco.application;

import br.com.banco.application.Commands.*;
import br.com.banco.application.port.*;
import br.com.banco.domain.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.*;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ContaCommandHandlerTest {

    /** Event store em memória; permite injetar conflitos para testar o retry. */
    static class InMemoryEventStore implements EventStore {
        final Map<String, List<ContaEvent>> streams = new HashMap<>();
        int conflitosAInjetar = 0;
        int appends = 0;
        final List<Integer> loadFromChamadas = new ArrayList<>();

        public synchronized EventStream load(String id) {
            var l = streams.getOrDefault(id, List.of());
            return new EventStream(id, l.size(), List.copyOf(l));
        }

        public synchronized EventStream loadFrom(String id, int afterVersion) {
            loadFromChamadas.add(afterVersion);
            var l = streams.getOrDefault(id, List.of());
            return new EventStream(id, Math.max(afterVersion, l.size()), List.copyOf(l.subList(afterVersion, l.size())));
        }

        @SuppressWarnings("unchecked")
        public synchronized void append(String id, int expected, List<? extends DomainEvent> events, EventMetadata m) {
            if (conflitosAInjetar > 0) { conflitosAInjetar--; throw new ConcurrencyException(id, expected); }
            if (!events.isEmpty() && events.get(0) instanceof TransferenciaEvent) {
                var t = transferencias.computeIfAbsent(id, k -> new ArrayList<>());
                if (t.size() != expected) throw new ConcurrencyException(id, expected);
                t.addAll((List<TransferenciaEvent>) events);
                appends++;
                return;
            }
            var l = streams.computeIfAbsent(id, k -> new ArrayList<>());
            if (l.size() != expected) throw new ConcurrencyException(id, expected);
            l.addAll((List<ContaEvent>) events);
            appends++;
        }

        public synchronized TransferenciaStream loadTransferencia(String id) {
            var l = transferencias.getOrDefault(id, List.of());
            return new TransferenciaStream(id, l.size(), List.copyOf(l));
        }

        final Map<String, List<TransferenciaEvent>> transferencias = new HashMap<>();

        int total() { return streams.values().stream().mapToInt(List::size).sum(); }
    }

    static class InMemoryIdempotency implements IdempotencyStore {
        final Map<String, CommandResult> m = new HashMap<>();
        public Optional<CommandResult> find(String k) { return Optional.ofNullable(m.get(k)); }
        public void save(String k, CommandResult r) { m.put(k, r); }
    }

    static class DirectUow implements UnitOfWork {
        public <T> T execute(Supplier<T> w) { return w.get(); }
    }

    InMemoryEventStore store;
    InMemoryIdempotency idem;
    ContaCommandHandler handler;
    final UUID id = UUID.randomUUID();

    private Context ctx(String key) { return new Context(key, UUID.randomUUID(), "teste"); }

    @BeforeEach
    void setup() {
        store = new InMemoryEventStore();
        idem = new InMemoryIdempotency();
        handler = new ContaCommandHandler(store, idem, new DirectUow(), Clock.system(ZoneOffset.UTC));
        handler.handle(new AbrirConta(id, "titular", BigDecimal.ZERO, ctx("abrir-1")));
    }

    private BigDecimal saldo() {
        return Conta.reconstruir(store.load("conta-" + id).events()).saldo();
    }

    @Test
    void CA01_depositoRetornaNovaVersao() {
        var r = handler.handle(new DepositarValor(id, new BigDecimal("50.00"), "CAIXA", ctx(null)));

        assertThat(r.versao()).isEqualTo(2);
        assertThat(saldo()).isEqualByComparingTo("50.00");
    }

    @Test
    void CA02_saqueSemSaldoNaoGravaEventos() {
        int antes = store.total();

        assertThatThrownBy(() -> handler.handle(new SacarValor(id, new BigDecimal("1"), ctx(null))))
                .isInstanceOf(SaldoInsuficienteException.class);
        assertThat(store.total()).isEqualTo(antes);
    }

    @Test
    void CA05_mesmaIdempotencyKeyNaoDuplicaEfeitos() {
        var cmd = new DepositarValor(id, new BigDecimal("10"), "CAIXA", ctx("K"));

        var primeira = handler.handle(cmd);
        int eventos = store.total();
        var segunda = handler.handle(cmd);

        assertThat(segunda).isEqualTo(primeira);
        assertThat(store.total()).isEqualTo(eventos);
        assertThat(saldo()).isEqualByComparingTo("10");
    }

    @Test
    void conflitoDeConcorrenciaEhRetentadoEDecisaoRefeita() {
        store.conflitosAInjetar = 2;

        handler.handle(new DepositarValor(id, BigDecimal.TEN, "X", ctx(null)));

        assertThat(saldo()).isEqualByComparingTo("10");
    }

    @Test
    void conflitoPersistenteExpoeConcurrencyExceptionAposTresTentativas() {
        store.conflitosAInjetar = 3;

        assertThatThrownBy(() -> handler.handle(new DepositarValor(id, BigDecimal.TEN, "X", ctx(null))))
                .isInstanceOf(ConcurrencyException.class);
        assertThat(saldo()).isEqualByComparingTo("0");
    }

    @Test
    void abrirContaDuasVezesComChavesDiferentesEhRejeitado() {
        assertThatThrownBy(() -> handler.handle(new AbrirConta(id, "t", BigDecimal.ZERO, ctx("outra"))))
                .isInstanceOf(ContaJaExisteException.class);
    }

    @Test
    void contaInexistenteRejeitaComandos() {
        assertThatThrownBy(() -> handler.handle(new DepositarValor(UUID.randomUUID(), BigDecimal.ONE, "X", ctx(null))))
                .isInstanceOf(ContaInvalidaException.class);
    }

    @Test
    void bloqueioEEncerramentoPassamPeloAgregado() {
        handler.handle(new BloquearConta(id, "fraude", ctx(null)));
        assertThatThrownBy(() -> handler.handle(new SacarValor(id, BigDecimal.ONE, ctx(null))))
                .isInstanceOf(ContaBloqueadaException.class);

        handler.handle(new DesbloquearConta(id, ctx(null)));
        handler.handle(new EncerrarConta(id, ctx(null)));
        assertThatThrownBy(() -> handler.handle(new DepositarValor(id, BigDecimal.ONE, "X", ctx(null))))
                .isInstanceOf(ContaInvalidaException.class);
    }
}

