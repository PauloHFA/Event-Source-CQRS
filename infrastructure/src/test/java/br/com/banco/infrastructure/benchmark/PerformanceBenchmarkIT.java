package br.com.banco.infrastructure.benchmark;

import br.com.banco.application.port.EventMetadata;
import br.com.banco.domain.Conta;
import br.com.banco.domain.ContaEvent;
import br.com.banco.domain.ContaSnapshot;
import br.com.banco.infrastructure.eventstore.EventSerializer;
import br.com.banco.infrastructure.eventstore.JdbcEventStore;
import br.com.banco.infrastructure.snapshots.JdbcSnapshotStore;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class PerformanceBenchmarkIT {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");

    static HikariDataSource ds;
    static JdbcTemplate jdbc;
    static TransactionTemplate tx;
    static JdbcEventStore store;
    static JdbcSnapshotStore snapshots;
    static EventSerializer serializer;
    static final Clock clock = Clock.systemUTC();
    static final EventMetadata meta = EventMetadata.of(UUID.randomUUID(), "benchmark");

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
        tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        serializer = new EventSerializer();
        store = new JdbcEventStore(jdbc, tx, serializer);
        snapshots = new JdbcSnapshotStore(jdbc, serializer);
    }

    @AfterAll
    static void tearDown() {
        ds.close();
    }

    @Test
    @DisplayName("RNF-06 (PostgreSQL real): Reconstrução com snapshot e 10.000 eventos persistidos deve ser < 50 ms")
    void rnf06_reconstrucaoEndToEndComBancoRealDeveSerInferiorA50Milissegundos() throws Exception {
        UUID contaId = UUID.randomUUID();
        String streamId = JdbcEventStore.contaStreamId(contaId);
        int totalEventos = 10_000;
        int versaoSnapshot = 9_900;
        Instant baseTime = Instant.parse("2026-01-01T00:00:00Z");

        // 1. Inserção em batch de 10.000 eventos no PostgreSQL
        String sql = """
                INSERT INTO events (event_id, stream_id, stream_type, version, event_type,
                                    schema_version, payload, metadata, occurred_at)
                VALUES (?, ?, 'Conta', ?, ?, 1, ?::jsonb, ?::jsonb, ?)
                """;
        String metaJson = serializer.toJson(meta);

        jdbc.execute((java.sql.Connection conn) -> {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                for (int v = 1; v <= totalEventos; v++) {
                    UUID evId = UUID.randomUUID();
                    ContaEvent ev = (v == 1)
                            ? new ContaEvent.ContaAberta(evId, contaId, "titular-10k", new BigDecimal("100.00"), baseTime)
                            : new ContaEvent.ValorDepositado(evId, contaId, new BigDecimal("1.00"), "BENCH", baseTime.plusSeconds(v));

                    ps.setObject(1, evId);
                    ps.setString(2, streamId);
                    ps.setInt(3, v);
                    ps.setString(4, ev.getClass().getSimpleName());
                    ps.setString(5, serializer.toJson(ev));
                    ps.setString(6, metaJson);
                    ps.setTimestamp(7, Timestamp.from(ev.em()));
                    ps.addBatch();

                    if (v % 1000 == 0) {
                        ps.executeBatch();
                    }
                }
                ps.executeBatch();
            }
            return null;
        });

        // 2. Persistir snapshot no PostgreSQL na versão 9.900
        ContaSnapshot snap = new ContaSnapshot(ContaSnapshot.SCHEMA_VERSION, contaId,
                new BigDecimal("9899.00"), new BigDecimal("100.00"), Conta.Status.ATIVA, versaoSnapshot);
        snapshots.save(streamId, snap);

        // 3. Warm-up
        for (int w = 0; w < 10; w++) {
            var s = snapshots.find(streamId).orElseThrow();
            var stream = store.loadFrom(streamId, s.versao());
            Conta.restaurar(s, stream.events());
        }

        // 4. Medição de 50 iterações de reidratação end-to-end com PostgreSQL
        List<Long> latenciasNanos = new ArrayList<>();
        int iteracoes = 50;

        for (int i = 0; i < iteracoes; i++) {
            long t0 = System.nanoTime();
            var s = snapshots.find(streamId).orElseThrow();
            var stream = store.loadFrom(streamId, s.versao());
            Conta conta = Conta.restaurar(s, stream.events());
            long duracao = System.nanoTime() - t0;

            latenciasNanos.add(duracao);
            assertThat(conta.versao()).isEqualTo(totalEventos);
        }

        Collections.sort(latenciasNanos);
        long p95Nanos = latenciasNanos.get((int) (iteracoes * 0.95));
        double p95Ms = p95Nanos / 1_000_000.0;
        double mediaMs = (latenciasNanos.stream().mapToLong(Long::longValue).sum() / (double) iteracoes) / 1_000_000.0;

        System.out.printf("[BENCHMARK RNF-06 - PostgreSQL Real] Reidratação de 10k eventos via Snapshot: Média = %.2f ms | p95 = %.2f ms (Meta: < 50 ms)%n",
                mediaMs, p95Ms);

        assertThat(p95Ms)
                .as("Latência p95 com banco real deve ser < 50 ms conforme meta RNF-06")
                .isLessThan(50.0);
    }

    @Test
    @DisplayName("RNF-05 & RNF-03: Throughput de escrita concorrente e latência p95 no Event Store")
    void rnf03_rnf05_throughputELatenciaDeEscritaConcorrente() throws Exception {
        int threads = 10;
        int eventosPorThread = 100;
        int totalOperacoes = threads * eventosPorThread; // 1.000 appends

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger concluidas = new AtomicInteger(0);
        List<Long> latenciasNanos = Collections.synchronizedList(new ArrayList<>());

        List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            futures.add(pool.submit(() -> {
                UUID id = UUID.randomUUID();
                String stream = JdbcEventStore.contaStreamId(id);
                // Cria a conta
                Conta c = Conta.abrir(id, "titular", BigDecimal.ZERO, clock);
                store.append(stream, 0, c.eventosPendentes(), meta);

                try {
                    latch.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }

                for (int i = 0; i < eventosPorThread; i++) {
                    long t0 = System.nanoTime();
                    var ev = List.of(new ContaEvent.ValorDepositado(UUID.randomUUID(), id, BigDecimal.ONE, "BENCH", Instant.now()));
                    store.append(stream, i + 1, ev, meta);
                    long duracao = System.nanoTime() - t0;

                    latenciasNanos.add(duracao);
                    concluidas.incrementAndGet();
                }
                return null;
            }));
        }

        long inicioGeral = System.nanoTime();
        latch.countDown(); // largada concorrente
        for (Future<?> f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }
        long tempoTotalNanos = System.nanoTime() - inicioGeral;
        pool.shutdown();

        double tempoTotalSegundos = tempoTotalNanos / 1_000_000_000.0;
        double throughput = totalOperacoes / tempoTotalSegundos;

        List<Long> ordenadas = new ArrayList<>(latenciasNanos);
        Collections.sort(ordenadas);
        double p95Ms = ordenadas.get((int) (ordenadas.size() * 0.95)) / 1_000_000.0;
        double p99Ms = ordenadas.get((int) (ordenadas.size() * 0.99)) / 1_000_000.0;

        System.out.printf("[BENCHMARK RNF-03 / RNF-05] Concorrência: %d ops em %.2fs | Throughput: %.0f eventos/s (Meta RNF-05: >= 1.000 ev/s) | Latência p95: %.2f ms (Meta RNF-03: < 100 ms) | p99: %.2f ms%n",
                totalOperacoes, tempoTotalSegundos, throughput, p95Ms, p99Ms);

        assertThat(p95Ms)
                .as("Latência p95 de escrita deve ser < 100 ms (RNF-03)")
                .isLessThan(100.0);
    }
}
