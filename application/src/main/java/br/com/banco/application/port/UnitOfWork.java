package br.com.banco.application.port;

import java.util.function.Supplier;

/** Fronteira transacional: append de eventos e registro de idempotência são atômicos. */
public interface UnitOfWork {
    <T> T execute(Supplier<T> work);
}

