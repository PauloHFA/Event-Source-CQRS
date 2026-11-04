package br.com.banco.domain;

import java.util.UUID;

public class TransferenciaEstadoInvalidaException extends DomainException {
    public TransferenciaEstadoInvalidaException(UUID transferId, String acao, Transferencia.Status atual) {
        super("Transferência " + transferId + " não pode '" + acao + "' no estado " + atual);
    }
}

