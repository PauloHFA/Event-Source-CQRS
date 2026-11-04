package br.com.banco.domain;

import java.time.Instant;
import java.util.UUID;

/** Fato imutável, nomeado no passado. */
public interface DomainEvent {
    UUID eventId();

    Instant em();

    /** Versão do esquema do payload (usada em upcasting). */
    default int schemaVersion() {
        return 1;
    }
}

