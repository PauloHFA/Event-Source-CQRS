package br.com.banco.application.port;

import br.com.banco.domain.DomainEvent;

import java.util.List;

public interface EventStore {

    /** Carrega o stream (snapshot + eventos posteriores, se houver). */
    EventStream load(String streamId);

    /** Eventos com versão maior que {@code afterVersion}; a versão do stream é afterVersion se não houver nenhum. */
    EventStream loadFrom(String streamId, int afterVersion);

    /** Stream da saga de transferência ({@code transfer-{uuid}}). */
    TransferenciaStream loadTransferencia(String streamId);

    /** Append atômico com concorrência otimista; lança {@link ConcurrencyException} em conflito. */
    void append(String streamId, int expectedVersion, List<? extends DomainEvent> events, EventMetadata meta);
}

