package br.com.banco.infrastructure.messaging;

/** JSON Schema do envelope público v1. O campo data fica aberto para novos tipos de evento. */
public final class IntegrationEventSchema {

    public static final String V1 = """
            {
              "$schema": "http://json-schema.org/draft-07/schema#",
              "title": "BancoIntegrationEventV1",
              "type": "object",
              "properties": {
                "eventId": { "type": "string", "format": "uuid" },
                "type": { "type": "string" },
                "streamId": { "type": "string" },
                "streamVersion": { "type": "integer" },
                "occurredAt": { "type": "string", "format": "date-time" },
                "correlationId": { "type": ["string", "null"], "format": "uuid" },
                "data": { "type": "object", "additionalProperties": true }
              },
              "required": ["eventId", "type", "streamId", "streamVersion", "occurredAt", "data"]
            }
            """;

    private IntegrationEventSchema() {}
}
