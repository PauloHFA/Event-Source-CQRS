package br.com.banco.infrastructure.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class KafkaSchemaRegistryIT {

    private static final Network NETWORK = Network.newNetwork();
    private static final ObjectMapper JSON = new ObjectMapper();

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer(
            DockerImageName.parse("confluentinc/cp-kafka:7.6.1"))
            .withNetwork(NETWORK)
            .withNetworkAliases("kafka");

    @Container
    static final GenericContainer<?> SCHEMA_REGISTRY = new GenericContainer<>(
            DockerImageName.parse("confluentinc/cp-schema-registry:7.6.1"))
            .withNetwork(NETWORK)
            .withNetworkAliases("schema-registry")
            .withExposedPorts(8081)
            .withEnv("SCHEMA_REGISTRY_HOST_NAME", "schema-registry")
            .withEnv("SCHEMA_REGISTRY_LISTENERS", "http://0.0.0.0:8081")
            .withEnv("SCHEMA_REGISTRY_KAFKASTORE_BOOTSTRAP_SERVERS", "PLAINTEXT://kafka:9092")
            .withEnv("SCHEMA_REGISTRY_KAFKASTORE_TOPIC_REPLICATION_FACTOR", "1")
            .dependsOn(KAFKA)
            .waitingFor(Wait.forHttp("/subjects").forPort(8081).forStatusCode(200))
            .withStartupTimeout(Duration.ofMinutes(3));

    @AfterAll
    static void closeNetwork() {
        NETWORK.close();
    }

    @Test
    void publicaEConsomeWireConfluentComSchemaEHeadersReais() throws Exception {
        String topic = "integration-test-" + UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();
        int schemaId;

        var registry = schemaRegistryClient();
        var event = new IntegrationEvent(eventId, "ValorDepositado", "conta-1", 2,
                Instant.parse("2026-01-01T00:00:00Z"), correlationId, Map.of("valor", "10.00"));
        try (AdminClient admin = AdminClient.create(adminProperties())) {
            admin.createTopics(java.util.List.of(new NewTopic(topic, 1, (short) 1)))
                    .all().get(30, TimeUnit.SECONDS);
        }

        try (var publisher = new KafkaIntegrationEventPublisher(KAFKA.getBootstrapServers(),
                "publisher-integration-test", registry)) {
            publisher.publish(topic, "conta-1", event);
        }

        schemaId = registry.registerJsonSchema(topic + "-value", IntegrationEventSchema.V1);
        try (KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(consumerProperties())) {
            consumer.subscribe(java.util.List.of(topic));
            ConsumerRecords<String, byte[]> records = consumer.poll(Duration.ZERO);
            long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
            while (records.isEmpty() && System.nanoTime() < deadline) {
                records = consumer.poll(Duration.ofSeconds(1));
            }

            assertThat(records).hasSize(1);
            var record = records.iterator().next();
            assertThat(record.key()).isEqualTo("conta-1");
            assertThat(new String(record.headers().lastHeader("eventId").value(), StandardCharsets.UTF_8))
                    .isEqualTo(eventId.toString());
            assertThat(new String(record.headers().lastHeader("correlationId").value(), StandardCharsets.UTF_8))
                    .isEqualTo(correlationId.toString());

            ByteBuffer wire = ByteBuffer.wrap(record.value());
            assertThat(wire.get()).isZero();
            assertThat(wire.getInt()).isEqualTo(schemaId);
            byte[] payload = new byte[wire.remaining()];
            wire.get(payload);
            JsonNode decoded = JSON.readTree(payload);
            assertThat(decoded.path("eventId").asText()).isEqualTo(eventId.toString());
            assertThat(decoded.path("type").asText()).isEqualTo("ValorDepositado");
            assertThat(decoded.path("data").path("valor").asText()).isEqualTo("10.00");
        }
    }

    @Test
    void schemaRegistryRealRejeitaVersaoIncompativel() throws Exception {
        String subject = "compatibility-test-" + UUID.randomUUID();
        HttpClient http = HttpClient.newHttpClient();
        URI baseUri = schemaRegistryUri();

        var registry = schemaRegistryClient();
        assertThat(registry.registerJsonSchema(subject, """
                {
                  "$schema": "http://json-schema.org/draft-07/schema#",
                  "type": "object",
                  "properties": {"eventId": {"type": "string"}},
                  "required": ["eventId"]
                }
                """)).isPositive();

        HttpRequest compatibility = HttpRequest.newBuilder(baseUri.resolve("config/" + subject))
                .header("Content-Type", "application/vnd.schemaregistry.v1+json")
                .PUT(HttpRequest.BodyPublishers.ofString("{\"compatibility\":\"BACKWARD_TRANSITIVE\"}"))
                .build();
        HttpResponse<String> compatibilityResponse = http.send(compatibility,
                HttpResponse.BodyHandlers.ofString());
        assertThat(compatibilityResponse.statusCode()).isBetween(200, 299);

        assertThatThrownBy(() -> registry.registerJsonSchema(subject, """
                {
                  "$schema": "http://json-schema.org/draft-07/schema#",
                  "type": "object",
                  "properties": {
                    "eventId": {"type": "string"},
                    "newRequired": {"type": "string"}
                  },
                  "required": ["eventId", "newRequired"]
                }
                """))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("HTTP 409");
    }

    private static SchemaRegistryClient schemaRegistryClient() {
        return new SchemaRegistryClient(schemaRegistryUri(), HttpClient.newHttpClient(), JSON, "", "");
    }

    private static URI schemaRegistryUri() {
        return URI.create("http://" + SCHEMA_REGISTRY.getHost() + ":"
                + SCHEMA_REGISTRY.getMappedPort(8081) + "/");
    }

    private static Properties adminProperties() {
        Properties properties = new Properties();
        properties.put("bootstrap.servers", KAFKA.getBootstrapServers());
        return properties;
    }

    private static Properties consumerProperties() {
        Properties properties = new Properties();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, "integration-test-" + UUID.randomUUID());
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        return properties;
    }
}
