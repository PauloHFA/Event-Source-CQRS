package br.com.banco.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Eventos do stream da saga de transferência ({@code transfer-{uuid}}). */
public sealed interface TransferenciaEvent extends DomainEvent {

    UUID transferId();

    record TransferenciaSolicitada(UUID eventId, UUID transferId, UUID origem, UUID destino,
                                   BigDecimal valor, Instant em) implements TransferenciaEvent {}

    record OrigemDebitada(UUID eventId, UUID transferId, Instant em) implements TransferenciaEvent {}

    record TransferenciaConcluida(UUID eventId, UUID transferId, Instant em) implements TransferenciaEvent {}

    /** O débito na origem foi recusado; nada foi movimentado. */
    record TransferenciaRejeitada(UUID eventId, UUID transferId, String motivo, Instant em)
            implements TransferenciaEvent {}

    /** O destino recusou o crédito; é necessário estornar a origem. */
    record CreditoDestinoFalhou(UUID eventId, UUID transferId, String motivo, Instant em)
            implements TransferenciaEvent {}

    record TransferenciaEstornada(UUID eventId, UUID transferId, Instant em) implements TransferenciaEvent {}

    /** Nem o estorno foi possível: saga travada que exige intervenção manual. */
    record CompensacaoFalhou(UUID eventId, UUID transferId, String motivo, Instant em)
            implements TransferenciaEvent {}
}

