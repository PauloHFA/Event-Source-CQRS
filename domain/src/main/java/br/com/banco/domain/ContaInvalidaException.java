package br.com.banco.domain;

import java.util.UUID;

public class ContaInvalidaException extends DomainException {
    public ContaInvalidaException(UUID contaId) {
        super("Conta inexistente ou encerrada: " + contaId);
    }
}
