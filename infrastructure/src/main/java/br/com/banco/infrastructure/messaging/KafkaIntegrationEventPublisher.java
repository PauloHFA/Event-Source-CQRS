package br.com.banco.infrastructure.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;

/** Publica eventos JSON no formato wire compatível com Confluent JSON Schema. */
public final class KafkaIntegrationEventPublisher implements IntegrationEventPublisher, AutoCloseable {

    private static final ObjectMapper JSON = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private final KafkaProducer<String, byte[]> producer;
    private final SchemaRegistryClient schemaRegistry;
    private final Map<String, Integer> schemaIds = new ConcurrentHashMap<>();

    public KafkaIntegrationEventPublisher(String bootstrapServers, String clientId,
                                          SchemaRegistryClient schemaRegistry) {
        this.schemaRegistry = schemaRegistry;
        this.producer = new KafkaProducer<>(producerProperties(bootstrapServers, clientId));
    }

    static Properties producerProperties(String bootstrapServers, String clientId) {
        Properties properties = new Properties();
        properties.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        properties.put(ProducerConfig.CLIENT_ID_CONFIG, clientId);
        properties.put(ProducerConfig.ACKS_CONFIG, "all");
        properties.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        properties.put(ProducerConfig.RETRIES_CONFIG, Integer.MAX_VALUE);
        properties.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);
        properties.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 30_000);
        properties.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 120_000);
        properties.put(ProducerConfig.RETRY_BACKOFF_MS_CONFIG, 100);
        properties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        properties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
        return properties;
    }

    @Override
    public void publish(String topic, String key, IntegrationEvent event) {
        String subject = topic + "-value";
        int schemaId = schemaIds.computeIfAbsent(subject,
                ignored -> schemaRegistry.registerJsonSchema(subject, IntegrationEventSchema.V1));
        byte[] value = encode(schemaId, event);
        ProducerRecord<String, byte[]> record = new ProducerRecord<>(topic, key, value);
        record.headers().add(new RecordHeader("eventId",
                event.eventId().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        record.headers().add(new RecordHeader("eventType",
                event.type().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        if (event.correlationId() != null) {
            record.headers().add(new RecordHeader("correlationId",
                    event.correlationId().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        }
        try {
            producer.send(record).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Publicação Kafka interrompida para o evento " + event.eventId(), e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("Falha ao publicar o evento " + event.eventId()
                    + " no tópico " + topic + ".", e.getCause());
        }
    }

    static byte[] encode(int schemaId, IntegrationEvent event) {
        try {
            byte[] payload = JSON.writeValueAsBytes(event);
            return ByteBuffer.allocate(5 + payload.length)
                    .put((byte) 0)
                    .putInt(schemaId)
                    .put(payload)
                    .array();
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Falha ao serializar o evento de integração " + event.eventId(), e);
        }
    }

    @Override
    public void close() {
        producer.close(Duration.ofSeconds(10));
    }
}
