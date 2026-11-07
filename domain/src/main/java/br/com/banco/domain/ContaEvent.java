package br.com.banco.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public sealed interface ContaEvent extends DomainEvent {

    UUID contaId();

    /** titularRef é uma referência (id), nunca nome/CPF (LGPD). */
    record ContaAberta(UUID eventId, UUID contaId, String titularRef,
                       BigDecimal limite, Instant em) implements ContaEvent {
        @Override
        public int schemaVersion() {
            return 2;
        }
    }

    record ValorDepositado(UUID eventId, UUID contaId, BigDecimal valor,
                           String origem, Instant em) implements ContaEvent {}

    record ValorSacado(UUID eventId, UUID contaId, BigDecimal valor,
                       Instant em) implements ContaEvent {}

    record ContaBloqueada(UUID eventId, UUID contaId, String motivo,
                          Instant em) implements ContaEvent {}

    record ContaDesbloqueada(UUID eventId, UUID contaId, Instant em) implements ContaEvent {}

    record ContaEncerrada(UUID eventId, UUID contaId, Instant em) implements ContaEvent {}

    /** Evento compensatório: devolve à conta de origem o valor de uma transferência que falhou. */
    record ValorEstornado(UUID eventId, UUID contaId, BigDecimal valor, UUID transferenciaId,
                          Instant em) implements ContaEvent {}

    /** RN-07: Limite diário de saque/transferência configurável por conta. */
    record LimiteDiarioConfigurado(UUID eventId, UUID contaId, BigDecimal novoLimite,
                                   Instant em) implements ContaEvent {}
}
