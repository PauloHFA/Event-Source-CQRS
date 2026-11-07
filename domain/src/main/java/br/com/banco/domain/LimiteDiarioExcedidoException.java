package br.com.banco.domain;

import java.math.BigDecimal;
import java.util.UUID;

public class LimiteDiarioExcedidoException extends DomainException {

    private final UUID contaId;
    private final BigDecimal valorTentado;
    private final BigDecimal limiteDiario;
    private final BigDecimal debitadoHoje;

    public LimiteDiarioExcedidoException(UUID contaId, BigDecimal valorTentado, BigDecimal limiteDiario, BigDecimal debitadoHoje) {
        super("Limite diário de débito excedido na conta " + contaId
                + ". Limite configurado: " + limiteDiario
                + ", total debitado hoje: " + debitadoHoje
                + ", tentativa: " + valorTentado);
        this.contaId = contaId;
        this.valorTentado = valorTentado;
        this.limiteDiario = limiteDiario;
        this.debitadoHoje = debitadoHoje;
    }

    public UUID getContaId() {
        return contaId;
    }

    public BigDecimal getValorTentado() {
        return valorTentado;
    }

    public BigDecimal getLimiteDiario() {
        return limiteDiario;
    }

    public BigDecimal getDebitadoHoje() {
        return debitadoHoje;
    }
}
