package br.com.banco.domain;

import java.util.UUID;

public class SaldoNaoZeradoException extends DomainException {
    public SaldoNaoZeradoException(UUID contaId) {
        super("Conta com saldo diferente de zero não pode ser encerrada: " + contaId);
    }
}
