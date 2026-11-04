package br.com.banco.domain;

import java.util.UUID;

public class TransferenciaMesmaContaException extends DomainException {
    public TransferenciaMesmaContaException(UUID contaId) {
        super("Transferência para a mesma conta é proibida: " + contaId);
    }
}

