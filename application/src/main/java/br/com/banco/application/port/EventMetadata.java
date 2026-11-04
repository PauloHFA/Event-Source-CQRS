package br.com.banco.application.port;

import java.util.UUID;

/** Metadados de auditoria gravados com cada evento (RF-11). */
public record EventMetadata(UUID correlationId, UUID causationId, String userId) {

    public static EventMetadata of(UUID correlationId, String userId) {
        return new EventMetadata(correlationId, null, userId);
    }
}
