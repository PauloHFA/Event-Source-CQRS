package br.com.banco.application.port;

import br.com.banco.application.CommandResult;

import java.util.Optional;

public interface IdempotencyStore {

    Optional<CommandResult> find(String key);

    /** Registra o resultado. Chave já existente (corrida) deve lançar {@link ConcurrencyException}. */
    void save(String key, CommandResult result);
}

