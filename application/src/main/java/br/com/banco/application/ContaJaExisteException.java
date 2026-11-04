package br.com.banco.application;

import br.com.banco.domain.DomainException;

import java.util.UUID;

public class ContaJaExisteException extends DomainException {
    public ContaJaExisteException(UUID contaId) {
        super("Conta já existe: " + contaId);
    }
}

