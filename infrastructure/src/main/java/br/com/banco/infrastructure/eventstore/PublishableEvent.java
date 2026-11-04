package br.com.banco.infrastructure.eventstore;

import br.com.banco.domain.DomainEvent;

import java.time.Instant;
import java.util.UUID;

/** Evento de qualquer agregado lido do log global, com metadados, para publicação. */
public record PublishableEvent(long globalPosition, String streamId, int version, DomainEvent event,
                               UUID correlationId, Instant occurredAt) {}
