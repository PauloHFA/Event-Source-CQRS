package br.com.banco.infrastructure.eventstore;

import br.com.banco.domain.ContaEvent;
import br.com.banco.domain.DomainEvent;
import br.com.banco.domain.TransferenciaEvent;
import br.com.banco.infrastructure.privacy.AesGcmPersonalDataCipher;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.databind.node.ObjectNode;

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
    private final EventUpcasterRegistry upcasters;
    private final AesGcmPersonalDataCipher personalDataCipher;

    public EventSerializer() {
        this(EventUpcasterRegistry.defaults(), null);
    }

    public EventSerializer(EventUpcasterRegistry upcasters, AesGcmPersonalDataCipher personalDataCipher) {
        this.upcasters = upcasters;
        this.personalDataCipher = personalDataCipher;
        Stream.concat(Stream.of(ContaEvent.class.getPermittedSubclasses()),
                        Stream.of(TransferenciaEvent.class.getPermittedSubclasses()))
                .forEach(c -> tipos.put(c.getSimpleName(), c.asSubclass(DomainEvent.class)));
    }

    public EventSerializer(AesGcmPersonalDataCipher personalDataCipher) {
        this(EventUpcasterRegistry.defaults(), personalDataCipher);
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

    public String serializeEvent(DomainEvent event) {
        JsonNode payload = mapper.valueToTree(event);
        if (event instanceof ContaEvent.ContaAberta opened) {
            ObjectNode object = (ObjectNode) payload;
            if (personalDataCipher == null) {
                object.put("titularRefProtection", "legacy-plaintext");
            } else {
                object.put("titularRefProtection", "aes-gcm-v1");
                object.put("titularRef", personalDataCipher.encrypt(
                        opened.contaId(), "titularRef", opened.titularRef()));
            }
        }
        try {
            return mapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Falha ao serializar " + typeOf(event), e);
        }
    }

    public DomainEvent deserializeAny(String eventType, String payloadJson) {
        return deserializeAny(null, eventType, 1, payloadJson);
    }

    public DomainEvent deserializeAny(String streamId, String eventType, int schemaVersion, String payloadJson) {
        Class<? extends DomainEvent> tipo = tipos.get(eventType);
        if (tipo == null) throw new IllegalStateException("Tipo de evento desconhecido: " + eventType);
        int currentVersion = currentSchemaVersion(eventType);
        JsonNode payload;
        try {
            payload = upcasters.upcast(eventType, schemaVersion, currentVersion, mapper.readTree(payloadJson));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Payload JSON inválido para " + eventType + ".", e);
        }
        if (eventType.equals(ContaEvent.ContaAberta.class.getSimpleName())) {
            ObjectNode object = (ObjectNode) payload;
            String protection = object.path("titularRefProtection").asText("");
            if ("aes-gcm-v1".equals(protection)) {
                if (personalDataCipher == null) {
                    throw new IllegalStateException("Cifra de dados pessoais não configurada para ler ContaAberta.");
                }
                String accountId = object.path("contaId").asText();
                object.put("titularRef", personalDataCipher.decrypt(
                        java.util.UUID.fromString(accountId), "titularRef",
                        object.path("titularRef").isNull() ? null : object.path("titularRef").asText()));
            } else if (!"legacy-plaintext".equals(protection)) {
                throw new IllegalStateException("Proteção titularRef desconhecida em " + eventType + ".");
            }
            object.remove("titularRefProtection");
        }
        return mapper.convertValue(payload, tipo);
    }

    public ContaEvent deserialize(String eventType, String payloadJson) {
        return (ContaEvent) deserializeAny(eventType, payloadJson);
    }

    public ContaEvent deserialize(String streamId, String eventType, int schemaVersion, String payloadJson) {
        return (ContaEvent) deserializeAny(streamId, eventType, schemaVersion, payloadJson);
    }

    public static int currentSchemaVersion(String eventType) {
        return eventType.equals(ContaEvent.ContaAberta.class.getSimpleName()) ? 2 : 1;
    }
}
