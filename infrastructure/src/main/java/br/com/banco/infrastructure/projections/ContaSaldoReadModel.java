package br.com.banco.infrastructure.projections;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record ContaSaldoReadModel(UUID contaId, BigDecimal saldo, BigDecimal limite,
                                  String status, int versao, Instant atualizadoEm) {}
