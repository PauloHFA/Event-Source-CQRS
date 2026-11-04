package br.com.banco.infrastructure.eventstore;

import br.com.banco.domain.ContaEvent;

import java.time.Instant;

/** Evento lido do log global, com posição para checkpoint de projeções. */
public record StoredEvent(long globalPosition, String streamId, int version, ContaEvent event,
                          Instant occurredAt) {}

