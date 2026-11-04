package br.com.banco.infrastructure.eventstore;

import com.fasterxml.jackson.databind.JsonNode;

public interface EventUpcaster {
    String eventType();

    int fromVersion();

    JsonNode upcast(JsonNode payload);
}
