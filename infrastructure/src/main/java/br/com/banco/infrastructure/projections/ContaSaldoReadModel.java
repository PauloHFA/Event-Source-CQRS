package br.com.banco.infrastructure.projections;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record ContaSaldoReadModel(UUID contaId, BigDecimal saldo, BigDecimal limite,
                                  BigDecimal limiteDiario, String status, int versao, Instant atualizadoEm) {

    public ContaSaldoReadModel(UUID contaId, BigDecimal saldo, BigDecimal limite,
                              String status, int versao, Instant atualizadoEm) {
        this(contaId, saldo, limite, null, status, versao, atualizadoEm);
    }
}
