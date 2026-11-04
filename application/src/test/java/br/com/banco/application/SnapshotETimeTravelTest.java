package br.com.banco.application;

import br.com.banco.application.Commands.*;
import br.com.banco.application.ContaCommandHandlerTest.*;
import br.com.banco.application.port.SnapshotStore;
import br.com.banco.domain.*;
import br.com.banco.domain.ContaEvent.*;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

class SnapshotETimeTravelTest {

    static class InMemorySnapshots implements SnapshotStore {
        final Map<String, ContaSnapshot> m = new HashMap<>();
        boolean falhar = false;
        public Optional<ContaSnapshot> find(String id) { return Optional.ofNullable(m.get(id)); }
        public void save(String id, ContaSnapshot s) {
            if (falhar) throw new IllegalStateException("banco fora");
            m.put(id, s);
        }
    }

    final InMemoryEventStore store = new InMemoryEventStore();
    final InMemorySnapshots snaps = new InMemorySnapshots();
    final UUID id = UUID.randomUUID();
    final ContaCommandHandler handler = new ContaCommandHandler(store, new InMemoryIdempotency(),
            new DirectUow(), Clock.system(ZoneOffset.UTC), snaps, Runnable::run, 3);

    private Context ctx() { return new Context(null, UUID.randomUUID(), "teste"); }

    private void abrirEDepositar(int depositos) {
        handler.handle(new AbrirConta(id, "t", BigDecimal.ZERO, ctx()));
        for (int i = 0; i < depositos; i++)
            handler.handle(new DepositarValor(id, new BigDecimal("10.00"), "X", ctx()));
    }

    @Test
    void snapshotEhGeradoAoCruzarOIntervaloEUsadoNasProximasReidratacoes() {
        abrirEDepositar(2); // versão 3 -> snapshot

        assertThat(snaps.m.get("conta-" + id).versao()).isEqualTo(3);

        handler.handle(new DepositarValor(id, new BigDecimal("5.00"), "X", ctx()));

        assertThat(store.loadFromChamadas).contains(3);
        assertThat(Conta.reconstruir(store.load("conta-" + id).events()).saldo()).isEqualByComparingTo("25.00");
    }

    @Test
    void resultadoEhIgualComOuSemSnapshot() {
        abrirEDepositar(8);
        var semSnapshot = Conta.reconstruir(store.load("conta-" + id).events()).snapshot();

        var viaSnapshot = Conta.restaurar(snaps.m.get("conta-" + id),
                store.loadFrom("conta-" + id, snaps.m.get("conta-" + id).versao()).events()).snapshot();

        assertThat(viaSnapshot).isEqualTo(semSnapshot);
    }

    @Test
    void falhaAoSalvarSnapshotNaoAfetaOComando() {
        snaps.falhar = true;

        abrirEDepositar(5);

        assertThat(store.load("conta-" + id).version()).isEqualTo(6);
    }

    @Test
    void CA07_timeTravelSomaApenasEventosAteAData() {
        var d1 = Instant.parse("2026-01-01T10:00:00Z");
        var d2 = Instant.parse("2026-01-10T10:00:00Z");
        var d3 = Instant.parse("2026-01-20T10:00:00Z");
        var hist = new InMemoryEventStore();
        hist.streams.put("conta-" + id, new ArrayList<>(List.of(
                new ContaAberta(UUID.randomUUID(), id, "t", BigDecimal.ZERO, d1),
                new ValorDepositado(UUID.randomUUID(), id, new BigDecimal("100"), "X", d1),
                new ValorDepositado(UUID.randomUUID(), id, new BigDecimal("50"), "X", d2),
                new ValorSacado(UUID.randomUUID(), id, new BigDecimal("30"), d3))));
        var servico = new ContaHistoricoService(hist);

        assertThat(servico.estadoEm(id, Instant.parse("2026-01-05T00:00:00Z")).saldo()).isEqualByComparingTo("100");
        assertThat(servico.estadoEm(id, d2).saldo()).isEqualByComparingTo("150"); // limite inclusivo
        assertThat(servico.estadoEm(id, Instant.parse("2026-02-01T00:00:00Z")).saldo()).isEqualByComparingTo("120");
        assertThat(servico.estadoEm(id, Instant.parse("2025-12-31T00:00:00Z")).status()).isEqualTo(Conta.Status.NOVA);
        assertThat(servico.estadoNaVersao(id, 3).saldo()).isEqualByComparingTo("150");
    }
}

