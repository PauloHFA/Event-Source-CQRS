package br.com.banco.domain;

import java.util.UUID;

public class ContaBloqueadaException extends DomainException {
    public ContaBloqueadaException(UUID contaId) {
        super("Conta bloqueada não aceita débito: " + contaId);
    }
}
