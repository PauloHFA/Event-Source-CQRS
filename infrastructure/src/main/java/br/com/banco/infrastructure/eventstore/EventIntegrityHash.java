package br.com.banco.infrastructure.eventstore;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/** Canonical event hashing shared by append and integrity verification. */
public final class EventIntegrityHash {

    private static final ObjectMapper JSON = new ObjectMapper();

    private EventIntegrityHash() {}

    public static byte[] calculate(byte[] previousHash, long position, UUID eventId, String streamId,
                                   int version, String eventType, int schemaVersion, String payload,
                                   String metadata, Instant occurredAt) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(bytes)) {
                writeBytes(out, previousHash);
                out.writeLong(position);
                writeString(out, eventId.toString());
                writeString(out, streamId);
                out.writeInt(version);
                writeString(out, eventType);
                out.writeInt(schemaVersion);
                writeString(out, canonicalJson(payload));
                writeString(out, canonicalJson(metadata));
                out.writeLong(occurredAt.getEpochSecond());
                out.writeInt(occurredAt.getNano());
            }
            return MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray());
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("Falha ao calcular hash de integridade do evento " + eventId, e);
        }
    }

    public static String canonicalJson(String json) {
        try {
            return JSON.writeValueAsString(canonicalize(JSON.readTree(json)));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("JSON inválido ao calcular hash de integridade.", e);
        }
    }

    private static JsonNode canonicalize(JsonNode node) {
        if (node.isObject()) {
            ObjectNode sorted = JSON.createObjectNode();
            Map<String, JsonNode> fields = new TreeMap<>();
            node.fields().forEachRemaining(entry -> fields.put(entry.getKey(), entry.getValue()));
            fields.forEach((name, value) -> sorted.set(name, canonicalize(value)));
            return sorted;
        }
        if (node.isArray()) {
            var array = JSON.createArrayNode();
            node.forEach(value -> array.add(canonicalize(value)));
            return array;
        }
        return node.deepCopy();
    }

    private static void writeString(DataOutputStream out, String value) throws IOException {
        writeBytes(out, value.getBytes(StandardCharsets.UTF_8));
    }

    private static void writeBytes(DataOutputStream out, byte[] value) throws IOException {
        if (value == null) {
            out.writeInt(-1);
        } else {
            out.writeInt(value.length);
            out.write(value);
        }
    }
}
