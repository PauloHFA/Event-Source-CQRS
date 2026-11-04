package br.com.banco.infrastructure.eventstore;

import br.com.banco.application.port.ConcurrencyException;
import br.com.banco.application.port.EventMetadata;
import br.com.banco.domain.Conta;
import br.com.banco.domain.ContaEvent;
import br.com.banco.domain.DomainException;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class JdbcEventStoreIT {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");

    static HikariDataSource ds;
    static JdbcTemplate jdbc;
    static JdbcEventStore store;
    final Clock clock = Clock.systemUTC();
    final EventMetadata meta = EventMetadata.of(UUID.randomUUID(), "teste");

    @BeforeAll
    static void setup() {
        var cfg = new HikariConfig();
        cfg.setJdbcUrl(PG.getJdbcUrl());
        cfg.setUsername(PG.getUsername());
        cfg.setPassword(PG.getPassword());
        cfg.setMaximumPoolSize(30);
        ds = new HikariDataSource(cfg);
        Flyway.configure().dataSource(ds).load().migrate();
        jdbc = new JdbcTemplate(ds);
        store = new JdbcEventStore(jdbc, new TransactionTemplate(new DataSourceTransactionManager(ds)),
                new EventSerializer());
    }

    @AfterAll
    static void tearDown() {
        ds.close();
    }

    private UUID abrirContaComSaldo(String saldo) {
        UUID id = UUID.randomUUID();
        Conta c = Conta.abrir(id, "titular", BigDecimal.ZERO, clock);
        c.depositar(new BigDecimal(saldo), "TESTE", clock);
        store.append(JdbcEventStore.contaStreamId(id), 0, c.eventosPendentes(), meta);
        return id;
    }

    @Test
    void appendELoadRoundTrip() {
        UUID id = abrirContaComSaldo("100.00");

        var stream = store.load(JdbcEventStore.contaStreamId(id));

        assertThat(stream.version()).isEqualTo(2);
        assertThat(stream.events()).hasSize(2);
        assertThat(Conta.reconstruir(stream.events()).saldo()).isEqualByComparingTo("100.00");
    }

    @Test
    void CA03_updateEDeleteSaoRejeitados() {
        abrirContaComSaldo("10");

        assertThatThrownBy(() -> jdbc.update("UPDATE events SET event_type = 'X'"))
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM events"))
                .hasMessageContaining("append-only");
    }

    @Test
    void versaoEsperadaDesatualizadaLancaConcurrencyException() {
        UUID id = abrirContaComSaldo("100");
        String streamId = JdbcEventStore.contaStreamId(id);
        var stream = store.load(streamId);
        Conta c = Conta.reconstruir(stream.events());
        c.depositar(BigDecimal.ONE, "X", clock);

        assertThatThrownBy(() -> store.append(streamId, 1, c.eventosPendentes(), meta))
                .isInstanceOf(ConcurrencyException.class);
        assertThat(store.load(streamId).events()).hasSize(2);
    }

    @Test
    void appendEhAtomico() {
        UUID id = abrirContaComSaldo("100");
        String streamId = JdbcEventStore.contaStreamId(id);
        Conta c = Conta.reconstruir(store.load(streamId).events());
        c.depositar(BigDecimal.ONE, "X", clock);
        c.depositar(BigDecimal.TEN, "X", clock);
        List<ContaEvent> dois = c.eventosPendentes();
        // reaproveitar a versão 3 colide com o evento já gravado
        store.append(streamId, 2, dois.subList(0, 1), meta);

        assertThatThrownBy(() -> store.append(streamId, 2, dois, meta))
                .isInstanceOf(ConcurrencyException.class);
        assertThat(store.load(streamId).events()).hasSize(3);
    }

    @Test
    void CA04_vinteSaquesConcorrentesApenasUmAceito() throws Exception {
        UUID id = abrirContaComSaldo("100.00");
        String streamId = JdbcEventStore.contaStreamId(id);
        int n = 20;
        var pool = Executors.newFixedThreadPool(n);
        var largada = new CountDownLatch(1);
        var aceitos = new AtomicInteger();
        List<Future<?>> futuros = new ArrayList<>();

        for (int i = 0; i < n; i++) {
            futuros.add(pool.submit(() -> {
                largada.await();
                var stream = store.load(streamId);
                Conta c = Conta.reconstruir(stream.events());
                try {
                    c.sacar(new BigDecimal("100.00"), clock);
                    store.append(streamId, stream.version(), c.eventosPendentes(), meta);
                    aceitos.incrementAndGet();
                } catch (DomainException | ConcurrencyException rejeitado) {
                    // esperado para todos menos um
                }
                return null;
            }));
        }
        largada.countDown();
        for (Future<?> f : futuros) f.get();
        pool.shutdown();

        var finalConta = Conta.reconstruir(store.load(streamId).events());
        assertThat(aceitos.get()).isEqualTo(1);
        assertThat(finalConta.saldo()).isEqualByComparingTo("0.00");
        long sacados = store.load(streamId).events().stream()
                .filter(e -> e instanceof ContaEvent.ValorSacado).count();
        assertThat(sacados).isEqualTo(1);
    }
}

