package br.com.banco.infrastructure.idempotency;

import br.com.banco.application.port.UnitOfWork;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.function.Supplier;

public class TransactionalUnitOfWork implements UnitOfWork {

    private final TransactionTemplate tx;

    public TransactionalUnitOfWork(TransactionTemplate tx) {
        this.tx = tx;
    }

    @Override
    public <T> T execute(Supplier<T> work) {
        return tx.execute(status -> work.get());
    }
}

