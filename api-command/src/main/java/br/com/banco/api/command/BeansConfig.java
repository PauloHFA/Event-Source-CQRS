package br.com.banco.api.command;

import br.com.banco.application.ContaCommandHandler;
import br.com.banco.application.ContaHistoricoService;
import br.com.banco.application.TransferenciaSaga;
import br.com.banco.infrastructure.transferencia.JdbcTransferenciaPendentes;
import br.com.banco.infrastructure.snapshots.JdbcSnapshotStore;
import org.springframework.beans.factory.annotation.Value;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import br.com.banco.application.port.IdempotencyStore;
import br.com.banco.application.port.UnitOfWork;
import br.com.banco.infrastructure.eventstore.EventSerializer;
import br.com.banco.infrastructure.eventstore.JdbcEventStore;
import br.com.banco.infrastructure.idempotency.JdbcIdempotencyStore;
import br.com.banco.infrastructure.idempotency.TransactionalUnitOfWork;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;

@Configuration
public class BeansConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    EventSerializer eventSerializer() {
        return new EventSerializer();
    }

    @Bean
    JdbcEventStore eventStore(JdbcTemplate jdbc, TransactionTemplate tx, EventSerializer serializer) {
        return new JdbcEventStore(jdbc, tx, serializer);
    }

    @Bean
    IdempotencyStore idempotencyStore(JdbcTemplate jdbc, EventSerializer serializer) {
        return new JdbcIdempotencyStore(jdbc, serializer);
    }

    @Bean
    UnitOfWork unitOfWork(TransactionTemplate tx) {
        return new TransactionalUnitOfWork(tx);
    }

    @Bean
    JdbcSnapshotStore snapshotStore(JdbcTemplate jdbc, EventSerializer serializer) {
        return new JdbcSnapshotStore(jdbc, serializer);
    }

    /** Snapshots são gerados fora do caminho do comando para não aumentar a latência. */
    @Bean(destroyMethod = "shutdown")
    ExecutorService snapshotExecutor() {
        return Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "snapshot-writer");
            t.setDaemon(true);
            return t;
        });
    }

    @Bean
    ContaCommandHandler contaCommandHandler(JdbcEventStore store, IdempotencyStore idempotency,
                                            UnitOfWork uow, Clock clock, JdbcSnapshotStore snapshots,
                                            ExecutorService snapshotExecutor,
                                            @Value("${banco.snapshot.interval:100}") int intervalo) {
        return new ContaCommandHandler(store, idempotency, uow, clock, snapshots, snapshotExecutor, intervalo);
    }

    @Bean
    JdbcTransferenciaPendentes transferenciaPendentes(JdbcTemplate jdbc) {
        return new JdbcTransferenciaPendentes(jdbc);
    }

    @Bean
    TransferenciaSaga transferenciaSaga(JdbcEventStore store, ContaCommandHandler contas,
                                        JdbcTransferenciaPendentes pendentes, Clock clock) {
        return new TransferenciaSaga(store, contas, pendentes, clock);
    }

    @Bean
    ContaHistoricoService contaHistoricoService(JdbcEventStore store) {
        return new ContaHistoricoService(store);
    }
}

