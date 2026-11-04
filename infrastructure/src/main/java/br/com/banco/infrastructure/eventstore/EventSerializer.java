package br.com.banco.infrastructure.eventstore;

import br.com.banco.domain.ContaEvent;
import br.com.banco.domain.DomainEvent;
import br.com.banco.domain.TransferenciaEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

/** Serialização JSON dos eventos; o domínio permanece livre de Jackson. */
public class EventSerializer {

    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    /** Nome simples do evento -> classe (nomes são únicos entre todos os agregados). */
    private final Map<String, Class<? extends DomainEvent>> tipos = new HashMap<>();

    public EventSerializer() {
        Stream.concat(Stream.of(ContaEvent.class.getPermittedSubclasses()),
                        Stream.of(TransferenciaEvent.class.getPermittedSubclasses()))
                .forEach(c -> tipos.put(c.getSimpleName(), c.asSubclass(DomainEvent.class)));
    }

    public String toJson(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Falha ao serializar " + o, e);
        }
    }

    public <T> T fromJson(String json, Class<T> type) {
        try {
            return mapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Falha ao desserializar " + type.getSimpleName(), e);
        }
    }

    public String typeOf(DomainEvent e) {
        return e.getClass().getSimpleName();
    }

    public DomainEvent deserializeAny(String eventType, String payloadJson) {
        Class<? extends DomainEvent> tipo = tipos.get(eventType);
        if (tipo == null) throw new IllegalStateException("Tipo de evento desconhecido: " + eventType);
        return fromJson(payloadJson, tipo);
    }

    public ContaEvent deserialize(String eventType, String payloadJson) {
        return (ContaEvent) deserializeAny(eventType, payloadJson);
    }
}
