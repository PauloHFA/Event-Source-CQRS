package br.com.banco.infrastructure.projections;

import br.com.banco.application.port.EventMetadata;
import br.com.banco.domain.Conta;
import br.com.banco.domain.ContaEvent;
import br.com.banco.infrastructure.eventstore.EventSerializer;
import br.com.banco.infrastructure.eventstore.JdbcEventStore;
import br.com.banco.infrastructure.eventstore.StoredEvent;
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
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class ProjectionIT {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");

    static HikariDataSource ds;
    static JdbcTemplate jdbc;
    static TransactionTemplate tx;
    static JdbcEventStore store;
    static ContaProjector projector;
    static ContaReadModelQueries queries;
    static ProjectionRunner runner;
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
        tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        store = new JdbcEventStore(jdbc, tx, new EventSerializer());
        projector = new ContaProjector(jdbc);
        queries = new ContaReadModelQueries(jdbc);
        runner = new ProjectionRunner(jdbc, tx, store, projector, 200);
    }

    @AfterAll
    static void tearDown() {
        ds.close();
    }

    private UUID abrirConta(String limite) {
        UUID id = UUID.randomUUID();
        Conta c = Conta.abrir(id, "titular", new BigDecimal(limite), clock);
        store.append(JdbcEventStore.contaStreamId(id), 0, c.eventosPendentes(), meta);
        return id;
    }

    private void operar(UUID id, java.util.function.Consumer<Conta> acao) {
        String streamId = JdbcEventStore.contaStreamId(id);
        var stream = store.load(streamId);
        Conta c = Conta.reconstruir(stream.events());
        acao.accept(c);
        store.append(streamId, stream.version(), c.eventosPendentes(), meta);
    }

    private BigDecimal saldoRm(UUID id) {
        return jdbc.queryForObject("SELECT saldo FROM rm_conta_saldo WHERE conta_id = ?", BigDecimal.class, id);
    }

    private List<Map<String, Object>> extratoSnapshot() {
        return jdbc.queryForList("SELECT conta_id, event_id, tipo, valor, saldo_apos, ocorrido_em FROM rm_extrato ORDER BY event_id");
    }

    @Test
    void CA01_readModelConvergeParaOSaldoDoAgregado() {
        UUID id = abrirConta("0");
        operar(id, c -> c.depositar(new BigDecimal("100.00"), "X", clock));
        operar(id, c -> c.depositar(new BigDecimal("50.00"), "X", clock));
        operar(id, c -> c.sacar(new BigDecimal("30.00"), clock));

        runner.runUntilCaughtUp();

        assertThat(saldoRm(id)).isEqualByComparingTo("120.00");
        var linhas = jdbc.queryForList("SELECT tipo, saldo_apos FROM rm_extrato WHERE conta_id = ? ORDER BY id", id);
        assertThat(linhas).extracting(l -> l.get("tipo")).containsExactly("DEPOSITO", "DEPOSITO", "SAQUE");
        assertThat((BigDecimal) linhas.get(2).get("saldo_apos")).isEqualByComparingTo("120.00");
    }

    @Test
    void statusDaContaEhProjetado() {
        UUID id = abrirConta("0");
        operar(id, c -> c.bloquear("fraude", clock));
        runner.runUntilCaughtUp();

        assertThat(jdbc.queryForObject("SELECT status FROM rm_conta_saldo WHERE conta_id = ?", String.class, id))
                .isEqualTo("BLOQUEADA");
    }

    @Test
    void saldoEExtratoSaoConsultadosDoReadModelComPaginacaoEstavel() {
        UUID id = abrirConta("0");
        Clock mesmoInstante = Clock.fixed(Instant.parse("2026-01-01T10:00:00Z"), ZoneOffset.UTC);
        operar(id, c -> c.depositar(new BigDecimal("10.00"), "X", mesmoInstante));
        operar(id, c -> c.depositar(new BigDecimal("15.00"), "X", mesmoInstante));
        runner.runUntilCaughtUp();

        assertThat(queries.saldo(id)).get()
                .extracting(ContaSaldoReadModel::saldo)
                .isEqualTo(new BigDecimal("25.00"));
        var primeiraPagina = queries.extrato(id, 0, 1);
        var segundaPagina = queries.extrato(id, 1, 1);

        assertThat(primeiraPagina.total()).isEqualTo(2);
        assertThat(primeiraPagina.movimentacoes()).singleElement()
                .extracting(MovimentacaoReadModel::saldoApos)
                .isEqualTo(new BigDecimal("25.00"));
        assertThat(segundaPagina.movimentacoes()).singleElement()
                .extracting(MovimentacaoReadModel::saldoApos)
                .isEqualTo(new BigDecimal("10.00"));
    }

    @Test
    void CA09_mesmoEventoEntregueDuasVezesNaoDuplicaNemSomaDeNovo() {
        UUID id = abrirConta("0");
        operar(id, c -> c.depositar(new BigDecimal("40.00"), "X", clock));
        runner.runUntilCaughtUp();

        StoredEvent deposito = store.readAll(0, 100_000).stream()
                .filter(s -> s.event() instanceof ContaEvent.ValorDepositado d && d.contaId().equals(id))
                .findFirst().orElseThrow();
        tx.executeWithoutResult(s -> {
            projector.on(deposito);
            projector.on(deposito);
        });

        assertThat(saldoRm(id)).isEqualByComparingTo("40.00");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM rm_extrato WHERE conta_id = ?", Integer.class, id))
                .isEqualTo(1);
    }

    @Test
    void CA08_reconstrucaoProduzConteudoIdentico() {
        UUID a = abrirConta("100");
        UUID b = abrirConta("0");
        operar(a, c -> c.depositar(new BigDecimal("10.00"), "X", clock));
        operar(a, c -> c.sacar(new BigDecimal("25.00"), clock));
        operar(b, c -> c.depositar(new BigDecimal("7.50"), "X", clock));
        runner.runUntilCaughtUp();
        var extratoAntes = extratoSnapshot();
        var saldosAntes = jdbc.queryForList("SELECT * FROM rm_conta_saldo ORDER BY conta_id");

        runner.rebuild();

        assertThat(extratoSnapshot()).isEqualTo(extratoAntes);
        assertThat(jdbc.queryForList("SELECT * FROM rm_conta_saldo ORDER BY conta_id")).isEqualTo(saldosAntes);
    }

    @Test
    void runnerRecriadoRetomaDoCheckpointPersistido() {
        runner.runUntilCaughtUp();
        UUID id = abrirConta("0");
        operar(id, c -> c.depositar(new BigDecimal("20.00"), "X", clock));

        ProjectionRunner antesDoReinicio = new ProjectionRunner(jdbc, tx, store, projector, 1);
        assertThat(antesDoReinicio.runOnce()).isEqualTo(1);
        long checkpointPersistido = antesDoReinicio.checkpoint();

        ProjectionRunner aposReinicio = new ProjectionRunner(jdbc, tx, store, projector, 200);
        assertThat(aposReinicio.checkpoint()).isEqualTo(checkpointPersistido);
        assertThat(aposReinicio.runUntilCaughtUp()).isPositive();

        assertThat(saldoRm(id)).isEqualByComparingTo("20.00");
        assertThat(aposReinicio.checkpoint()).isEqualTo(
                jdbc.queryForObject("SELECT max(global_position) FROM events", Long.class));
    }

    @Test
    void CA14_milEventosConcorrentesNenhumEventoEhPulado() throws Exception {
        int threads = 20, contasPorThread = 5, depositosPorConta = 10; // 1.000 eventos ValorDepositado
        List<UUID> contas = new ArrayList<>();
        for (int i = 0; i < threads * contasPorThread; i++) contas.add(abrirConta("0"));
        runner.runUntilCaughtUp();

        var pool = Executors.newFixedThreadPool(threads + 1);
        var terminou = new AtomicBoolean(false);
        // projetor rodando concorrentemente com a escrita
        Future<?> projetando = pool.submit(() -> {
            while (!terminou.get()) {
                if (runner.runOnce() == 0) Thread.sleep(5);
            }
            return null;
        });
        List<Future<?>> escritores = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            var minhas = contas.subList(t * contasPorThread, (t + 1) * contasPorThread);
            escritores.add(pool.submit(() -> {
                for (UUID id : minhas)
                    for (int k = 0; k < depositosPorConta; k++)
                        operar(id, c -> c.depositar(new BigDecimal("1.00"), "X", clock));
                return null;
            }));
        }
        for (Future<?> f : escritores) f.get();
        terminou.set(true);
        projetando.get();
        pool.shutdown();
        runner.runUntilCaughtUp();

        // contagem
        Long eventosNoLog = jdbc.queryForObject(
                "SELECT count(*) FROM events WHERE event_type = 'ValorDepositado' AND stream_id = ANY (?)",
                Long.class, (Object) contas.stream().map(JdbcEventStore::contaStreamId).toArray(String[]::new));
        Long linhasNoExtrato = jdbc.queryForObject(
                "SELECT count(*) FROM rm_extrato WHERE conta_id = ANY (?)", Long.class,
                (Object) contas.toArray(UUID[]::new));
        assertThat(eventosNoLog).isEqualTo(1000);
        assertThat(linhasNoExtrato).isEqualTo(1000);
        // checksum: soma dos saldos projetados
        BigDecimal soma = jdbc.queryForObject("SELECT sum(saldo) FROM rm_conta_saldo WHERE conta_id = ANY (?)",
                BigDecimal.class, (Object) contas.toArray(UUID[]::new));
        assertThat(soma).isEqualByComparingTo("1000.00");
        // checkpoint alcançou o fim do log
        Long max = jdbc.queryForObject("SELECT max(global_position) FROM events", Long.class);
        assertThat(runner.checkpoint()).isEqualTo(max);
    }
}
