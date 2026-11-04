package br.com.banco.infrastructure.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;

/** Registra esquemas JSON usando a API REST do Confluent Schema Registry. */
public final class SchemaRegistryClient {

    private static final String CONTENT_TYPE = "application/vnd.schemaregistry.v1+json";

    private final URI baseUri;
    private final HttpClient http;
    private final ObjectMapper json;
    private final String authorization;

    public SchemaRegistryClient(URI baseUri, HttpClient http, ObjectMapper json,
                                String username, String password) {
        String base = baseUri.toString();
        this.baseUri = URI.create(base.endsWith("/") ? base : base + "/");
        this.http = http;
        this.json = json;
        if ((username == null || username.isBlank()) != (password == null || password.isBlank())) {
            throw new IllegalArgumentException("Informe usuário e senha do Schema Registry juntos.");
        }
        this.authorization = username == null || username.isBlank() ? null : "Basic " + Base64.getEncoder()
                .encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

    public int registerJsonSchema(String subject, String schema) {
        String endpoint = "subjects/" + URLEncoder.encode(subject, StandardCharsets.UTF_8).replace("+", "%20")
                + "/versions";
        String requestBody;
        try {
            requestBody = json.writeValueAsString(Map.of("schemaType", "JSON", "schema", schema));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Falha ao criar solicitação de registro do JSON Schema.", e);
        }

        HttpRequest.Builder request = HttpRequest.newBuilder(baseUri.resolve(endpoint))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", CONTENT_TYPE)
                .header("Accept", CONTENT_TYPE)
                .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8));
        if (authorization != null) request.header("Authorization", authorization);

        HttpResponse<String> response;
        try {
            response = http.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Registro de esquema no Schema Registry foi interrompido.", e);
        } catch (IOException e) {
            throw new IllegalStateException("Falha ao conectar ao Schema Registry em " + baseUri + ".", e);
        }

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("Schema Registry rejeitou o esquema (HTTP "
                    + response.statusCode() + "): " + response.body());
        }
        try {
            JsonNode id = json.readTree(response.body()).get("id");
            if (id == null || !id.canConvertToInt() || id.intValue() < 0) {
                throw new IllegalStateException("Resposta inválida do Schema Registry: campo id ausente.");
            }
            return id.intValue();
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Resposta JSON inválida do Schema Registry.", e);
        }
    }
}
