package br.com.banco.infrastructure.transferencia;

import br.com.banco.application.Commands.*;
import br.com.banco.application.ContaCommandHandler;
import br.com.banco.application.TransferenciaSaga;
import br.com.banco.domain.Conta;
import br.com.banco.domain.Transferencia;
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
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class TransferenciaSagaIT {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");

    static HikariDataSource ds;
    static JdbcTemplate jdbc;
    static JdbcEventStore store;
    static ContaCommandHandler contas;
    static TransferenciaSaga saga;
    static JdbcTransferenciaPendentes pendentes;
    static final Clock clock = Clock.systemUTC();

    @BeforeAll
    static void setup() {
        var cfg = new HikariConfig();
        cfg.setJdbcUrl(PG.getJdbcUrl());
        cfg.setUsername(PG.getUsername());
        cfg.setPassword(PG.getPassword());
        cfg.setMaximumPoolSize(20);
        ds = new HikariDataSource(cfg);
        Flyway.configure().dataSource(ds).load().migrate();
        jdbc = new JdbcTemplate(ds);
        var tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        var ser = new EventSerializer();
        store = new JdbcEventStore(jdbc, tx, ser);
        contas = new ContaCommandHandler(store, new JdbcIdempotencyStore(jdbc, ser),
                new TransactionalUnitOfWork(tx), clock);
        pendentes = new JdbcTransferenciaPendentes(jdbc);
        saga = new TransferenciaSaga(store, contas, pendentes, clock);
    }

    @AfterAll
    static void tearDown() {
        ds.close();
    }

    private Context ctx() { return new Context(null, UUID.randomUUID(), "teste"); }

    private UUID conta(String saldoInicial) {
        UUID id = UUID.randomUUID();
        contas.handle(new AbrirConta(id, "t", BigDecimal.ZERO, ctx()));
        if (new BigDecimal(saldoInicial).signum() > 0)
            contas.handle(new DepositarValor(id, new BigDecimal(saldoInicial), "X", ctx()));
        return id;
    }

    private BigDecimal saldo(UUID id) {
        return Conta.reconstruir(store.load("conta-" + id).events()).saldo();
    }

    @Test
    void CA10_transferenciaComSucesso() {
        UUID a = conta("200.00"), b = conta("0");

        var r = saga.solicitar(new SolicitarTransferencia(UUID.randomUUID(), a, b, new BigDecimal("80.00"), ctx()));

        assertThat(r.status()).isEqualTo(Transferencia.Status.CONCLUIDA);
        assertThat(saldo(a)).isEqualByComparingTo("120.00");
        assertThat(saldo(b)).isEqualByComparingTo("80.00");
    }

    @Test
    void CA11_compensacaoQuandoDestinoEstaEncerrado() {
        UUID a = conta("200.00"), b = conta("0");
        contas.handle(new EncerrarConta(b, ctx()));

        var r = saga.solicitar(new SolicitarTransferencia(UUID.randomUUID(), a, b, new BigDecimal("80.00"), ctx()));

        assertThat(r.status()).isEqualTo(Transferencia.Status.ESTORNADA);
        assertThat(saldo(a)).isEqualByComparingTo("200.00");
        long sacados = store.load("conta-" + a).events().stream()
                .filter(e -> e.getClass().getSimpleName().equals("ValorSacado")).count();
        long estornos = store.load("conta-" + a).events().stream()
                .filter(e -> e.getClass().getSimpleName().equals("ValorEstornado")).count();
        assertThat(sacados).isEqualTo(1);
        assertThat(estornos).isEqualTo(1);
    }

    @Test
    void transferenciasConcorrentesConservamOTotal() throws Exception {
        UUID a = conta("1000.00"), b = conta("1000.00");
        var pool = Executors.newFixedThreadPool(10);
        List<Future<?>> fs = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            boolean aParaB = i % 2 == 0;
            fs.add(pool.submit(() -> saga.solicitar(new SolicitarTransferencia(UUID.randomUUID(),
                    aParaB ? a : b, aParaB ? b : a, new BigDecimal("10.00"), ctx()))));
        }
        for (Future<?> f : fs) f.get();
        pool.shutdown();
        saga.recuperarTravadas(Duration.ZERO); // conclui eventuais sagas que esgotaram os passos sob contenção

        assertThat(saldo(a).add(saldo(b))).isEqualByComparingTo("2000.00");
        assertThat(saldo(a)).isEqualByComparingTo("1000.00"); // 10 idas e 10 voltas de 10,00
    }

    @Test
    void sagaNaoTerminalApareceComoPendente() {
        UUID a = conta("200.00"), b = conta("0");
        UUID id = UUID.randomUUID();
        var t = Transferencia.solicitar(id, a, b, new BigDecimal("80.00"), clock);
        store.append("transfer-" + id, 0, t.eventosPendentes(),
                br.com.banco.application.port.EventMetadata.of(id, "teste"));

        assertThat(pendentes.pendentesAntesDe(Instant.now().plusSeconds(60))).contains(id);
        assertThat(pendentes.pendentesAntesDe(Instant.now().minus(Duration.ofDays(1)))).doesNotContain(id);

        saga.recuperarTravadas(Duration.ZERO);

        assertThat(saga.consultar(id).status()).isEqualTo(Transferencia.Status.CONCLUIDA);
        assertThat(pendentes.pendentesAntesDe(Instant.now().plusSeconds(60))).doesNotContain(id);
    }
}
