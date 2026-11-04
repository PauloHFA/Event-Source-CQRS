package br.com.banco.infrastructure.eventstore;

import br.com.banco.domain.ContaEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Applies an explicit, linear chain of event schema upgrades without changing stored rows. */
public final class EventUpcasterRegistry {

    private final Map<String, Function<JsonNode, JsonNode>> upcasters;

    public EventUpcasterRegistry(List<EventUpcaster> registered) {
        this.upcasters = registered.stream().collect(Collectors.toUnmodifiableMap(
                upcaster -> key(upcaster.eventType(), upcaster.fromVersion()),
                upcaster -> upcaster::upcast));
    }

    public static EventUpcasterRegistry defaults() {
        return new EventUpcasterRegistry(List.of(new ContaAbertaV1ToV2()));
    }

    public JsonNode upcast(String eventType, int storedVersion, int currentVersion, JsonNode payload) {
        if (storedVersion < 1 || currentVersion < storedVersion) {
            throw new IllegalStateException("Versão de schema inválida para " + eventType + ": "
                    + storedVersion + " (atual " + currentVersion + ").");
        }
        JsonNode current = payload.deepCopy();
        for (int version = storedVersion; version < currentVersion; version++) {
            Function<JsonNode, JsonNode> upcaster = upcasters.get(key(eventType, version));
            if (upcaster == null) {
                throw new IllegalStateException("Upcaster ausente para " + eventType + " v"
                        + version + " -> v" + (version + 1) + ".");
            }
            current = upcaster.apply(current);
        }
        return current;
    }

    private static String key(String eventType, int version) {
        return eventType + ":" + version;
    }

    private static final class ContaAbertaV1ToV2 implements EventUpcaster {
        @Override
        public String eventType() {
            return ContaEvent.ContaAberta.class.getSimpleName();
        }

        @Override
        public int fromVersion() {
            return 1;
        }

        @Override
        public JsonNode upcast(JsonNode payload) {
            if (!(payload instanceof ObjectNode object)) {
                throw new IllegalStateException("Payload ContaAberta v1 deve ser um objeto JSON.");
            }
            object.put("titularRefProtection", "legacy-plaintext");
            return object;
        }
    }
}
