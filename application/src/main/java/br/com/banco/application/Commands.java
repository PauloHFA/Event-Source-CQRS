package br.com.banco.application;

import java.math.BigDecimal;
import java.util.UUID;

/** Comandos (intenção, no imperativo). Podem ser rejeitados. */
public final class Commands {
    private Commands() {}

    /** Dados comuns de rastreabilidade/idempotência. */
    public record Context(String idempotencyKey, UUID correlationId, String userId) {}

    public record AbrirConta(UUID contaId, String titularRef, BigDecimal limite, Context ctx) {}

    public record ConfigurarLimiteDiario(UUID contaId, BigDecimal novoLimite, Context ctx) {}

    public record DepositarValor(UUID contaId, BigDecimal valor, String origem, Context ctx) {}

    public record SacarValor(UUID contaId, BigDecimal valor, Context ctx) {}

    public record BloquearConta(UUID contaId, String motivo, Context ctx) {}

    public record DesbloquearConta(UUID contaId, Context ctx) {}

    public record EncerrarConta(UUID contaId, Context ctx) {}

    /** Compensação de transferência: devolve o valor debitado na origem. */
    public record EstornarValor(UUID contaId, BigDecimal valor, UUID transferenciaId, Context ctx) {}

    public record SolicitarTransferencia(UUID transferId, UUID origem, UUID destino, BigDecimal valor,
                                         Context ctx) {}
}
