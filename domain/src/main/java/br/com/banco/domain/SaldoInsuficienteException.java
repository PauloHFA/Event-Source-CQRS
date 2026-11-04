package br.com.banco.domain;

import java.util.UUID;

public class SaldoInsuficienteException extends DomainException {
    public SaldoInsuficienteException(UUID contaId) {
        super("Saldo insuficiente na conta " + contaId);
    }
}
