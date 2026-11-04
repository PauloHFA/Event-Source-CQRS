package br.com.banco.domain;

import java.math.BigDecimal;

public class ValorInvalidoException extends DomainException {
    public ValorInvalidoException(BigDecimal valor) {
        super("Valor deve ser positivo: " + valor);
    }
}

