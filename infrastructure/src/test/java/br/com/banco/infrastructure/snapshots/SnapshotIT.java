package br.com.banco.infrastructure.snapshots;

import br.com.banco.application.ContaCommandHandler;
import br.com.banco.application.Commands.*;
import br.com.banco.application.ContaHistoricoService;
import br.com.banco.application.port.IdempotencyStore;
import br.com.banco.domain.Conta;
import br.com.banco.domain.ContaSnapshot;
import br.com.banco.infrastructure.eventstore.EventSerializer;
import br.com.banco.infrastructure.eventstore.JdbcEventStore;
import br.com.banco.infrastructure.idempotency.JdbcIdempotencyStore;
import br.com.banco.infrastructure.idempotency.TransactionalUnitOfWork;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class SnapshotIT {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");

    static HikariDataSource ds;
    static JdbcTemplate jdbc;
    static JdbcEventStore store;
    static JdbcSnapshotStore snapshots;
    static ContaCommandHandler handler;

    @BeforeAll
    static void setup() {
        var cfg = new HikariConfig();
        cfg.setJdbcUrl(PG.getJdbcUrl());
        cfg.setUsername(PG.getUsername());
        cfg.setPassword(PG.getPassword());
        ds = new HikariDataSource(cfg);
        Flyway.configure().dataSource(ds).load().migrate();
        jdbc = new JdbcTemplate(ds);
        var tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        var ser = new EventSerializer();
        store = new JdbcEventStore(jdbc, tx, ser);
        snapshots = new JdbcSnapshotStore(jdbc, ser);
        IdempotencyStore idem = new JdbcIdempotencyStore(jdbc, ser);
        handler = new ContaCommandHandler(store, idem, new TransactionalUnitOfWork(tx), Clock.systemUTC(),
                snapshots, Runnable::run, 5);
    }

    @AfterAll
    static void tearDown() {
        ds.close();
    }

    private Context ctx() { return new Context(null, UUID.randomUUID(), "teste"); }

    @Test
    void snapshotEhGravadoNaoRetrocedeESubstituiPorVersaoMaisNova() {
        UUID id = UUID.randomUUID();
        handler.handle(new AbrirConta(id, "t", BigDecimal.ZERO, ctx()));
        for (int i = 0; i < 4; i++) handler.handle(new DepositarValor(id, BigDecimal.TEN, "X", ctx()));
        String stream = "conta-" + id;

        var snap = snapshots.find(stream).orElseThrow();
        assertThat(snap.versao()).isEqualTo(5);
        assertThat(snap.saldo()).isEqualByComparingTo("40");

        // um snapshot mais antigo não sobrescreve o mais novo
        snapshots.save(stream, new ContaSnapshot(ContaSnapshot.SCHEMA_VERSION, id, BigDecimal.ONE,
                BigDecimal.ZERO, Conta.Status.ATIVA, 2));
        assertThat(snapshots.find(stream).orElseThrow().versao()).isEqualTo(5);
    }

    @Test
    void snapshotDeEsquemaDiferenteEhIgnorado() {
        UUID id = UUID.randomUUID();
        snapshots.save("conta-" + id, new ContaSnapshot(99, id, BigDecimal.ONE, BigDecimal.ZERO,
                Conta.Status.ATIVA, 3));

        assertThat(snapshots.find("conta-" + id)).isEmpty();
    }

    @Test
    void comandosComSnapshotProduzemMesmoEstadoQueReplayCompleto() {
        UUID id = UUID.randomUUID();
        handler.handle(new AbrirConta(id, "t", BigDecimal.ZERO, ctx()));
        for (int i = 0; i < 12; i++) handler.handle(new DepositarValor(id, new BigDecimal("2.50"), "X", ctx()));

        var replay = Conta.reconstruir(store.load("conta-" + id).events()).snapshot();
        assertThat(replay.saldo()).isEqualByComparingTo("30.00");
        assertThat(replay.versao()).isEqualTo(13);
        var historico = new ContaHistoricoService(store);
        assertThat(historico.estadoEm(id, Instant.now().plusSeconds(60))).isEqualTo(replay);
        assertThat(historico.estadoNaVersao(id, 5).saldo()).isEqualByComparingTo("10.00");
    }
}

