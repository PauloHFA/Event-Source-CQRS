package br.com.banco.infrastructure.messaging;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Evento de integração: contrato público, estável e enxuto, independente dos eventos de domínio
 * (ADR-007). {@code eventId} é o messageId para deduplicação (Inbox) nos consumidores.
 */
public record IntegrationEvent(UUID eventId, String type, String streamId, int streamVersion,
                               Instant occurredAt, UUID correlationId, Map<String, Object> data) {}
