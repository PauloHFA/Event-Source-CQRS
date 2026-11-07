package br.com.banco.infrastructure.messaging;

import br.com.banco.domain.ContaEvent;
import br.com.banco.domain.DomainEvent;
import br.com.banco.domain.TransferenciaEvent;
import br.com.banco.infrastructure.eventstore.PublishableEvent;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Traduz eventos de domínio em eventos de integração. Eventos puramente internos (passos da saga)
 * não são publicados, e dados pessoais/referências de titular nunca saem daqui.
 */
public class IntegrationEventMapper {

    public static final String TOPIC_CONTA = "banco.conta.eventos.v1";
    public static final String TOPIC_TRANSFERENCIA = "banco.transferencia.eventos.v1";

    public record Mapped(String topic, IntegrationEvent event) {}

    public Optional<Mapped> map(PublishableEvent p) {
        DomainEvent e = p.event();
        if (e instanceof ContaEvent c) return Optional.of(conta(p, c));
        if (e instanceof TransferenciaEvent t) return transferencia(p, t);
        throw new IllegalArgumentException("Tipo de evento sem mapeamento de integração: "
                + e.getClass().getName() + " (" + e.eventId() + ").");
    }

    private Mapped conta(PublishableEvent p, ContaEvent c) {
        return switch (c) {
            case ContaEvent.ContaAberta e ->
                    pub(p, TOPIC_CONTA, "ContaAberta", dados("contaId", e.contaId(), "limite", dec(e.limite())));
            case ContaEvent.ValorDepositado e ->
                    pub(p, TOPIC_CONTA, "ValorDepositado",
                            dados("contaId", e.contaId(), "valor", dec(e.valor()), "origem", e.origem()));
            case ContaEvent.ValorSacado e ->
                    pub(p, TOPIC_CONTA, "ValorSacado", dados("contaId", e.contaId(), "valor", dec(e.valor())));
            case ContaEvent.ValorEstornado e ->
                    pub(p, TOPIC_CONTA, "ValorEstornado",
                            dados("contaId", e.contaId(), "valor", dec(e.valor()),
                                    "transferenciaId", e.transferenciaId()));
            case ContaEvent.ContaBloqueada e ->
                    pub(p, TOPIC_CONTA, "ContaBloqueada", dados("contaId", e.contaId(), "motivo", e.motivo()));
            case ContaEvent.ContaDesbloqueada e ->
                    pub(p, TOPIC_CONTA, "ContaDesbloqueada", dados("contaId", e.contaId()));
            case ContaEvent.ContaEncerrada e ->
                    pub(p, TOPIC_CONTA, "ContaEncerrada", dados("contaId", e.contaId()));
            case ContaEvent.LimiteDiarioConfigurado e ->
                    pub(p, TOPIC_CONTA, "LimiteDiarioConfigurado",
                            dados("contaId", e.contaId(), "novoLimite", e.novoLimite() != null ? dec(e.novoLimite()) : null));
        };
    }

    private Optional<Mapped> transferencia(PublishableEvent p, TransferenciaEvent t) {
        return switch (t) {
            case TransferenciaEvent.TransferenciaSolicitada e -> Optional.of(pub(p, TOPIC_TRANSFERENCIA,
                    "TransferenciaSolicitada", dados("transferId", e.transferId(), "origem", e.origem(),
                            "destino", e.destino(), "valor", dec(e.valor()))));
            case TransferenciaEvent.TransferenciaConcluida e -> Optional.of(pub(p, TOPIC_TRANSFERENCIA,
                    "TransferenciaConcluida", dados("transferId", e.transferId())));
            case TransferenciaEvent.TransferenciaRejeitada e -> Optional.of(pub(p, TOPIC_TRANSFERENCIA,
                    "TransferenciaRejeitada", dados("transferId", e.transferId(), "motivo", e.motivo())));
            case TransferenciaEvent.TransferenciaEstornada e -> Optional.of(pub(p, TOPIC_TRANSFERENCIA,
                    "TransferenciaEstornada", dados("transferId", e.transferId())));
            case TransferenciaEvent.CompensacaoFalhou e -> Optional.of(pub(p, TOPIC_TRANSFERENCIA,
                    "CompensacaoFalhou", dados("transferId", e.transferId(), "motivo", e.motivo())));
            // passos internos da saga: não fazem parte do contrato público
            case TransferenciaEvent.OrigemDebitada e -> Optional.empty();
            case TransferenciaEvent.CreditoDestinoFalhou e -> Optional.empty();
        };
    }

    private static Mapped pub(PublishableEvent p, String topic, String type, Map<String, Object> data) {
        return new Mapped(topic, new IntegrationEvent(p.event().eventId(), type, p.streamId(), p.version(),
                p.occurredAt(), p.correlationId(), data));
    }

    /** Valores monetários trafegam como string decimal, sem perda de precisão. */
    private static String dec(BigDecimal v) {
        return v.toPlainString();
    }

    /** Mapa ordenado ignorando valores nulos. */
    private static Map<String, Object> dados(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            if (kv[i + 1] != null) m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }
}
