package br.com.banco.infrastructure.projections;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record MovimentacaoReadModel(UUID eventId, String tipo, BigDecimal valor,
                                   BigDecimal saldoApos, Instant ocorridoEm) {}
