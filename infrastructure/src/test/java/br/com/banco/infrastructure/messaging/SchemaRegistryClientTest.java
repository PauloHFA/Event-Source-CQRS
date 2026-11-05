package br.com.banco.infrastructure.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SchemaRegistryClientTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void registraJsonSchemaComSubjectEAutenticacaoBasica() throws Exception {
        AtomicReference<String> requestPath = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requestPath.set(exchange.getRequestURI().getPath());
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "{\"id\":42}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/vnd.schemaregistry.v1+json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        String username = "registry-user";
        String password = UUID.randomUUID().toString();
        var client = new SchemaRegistryClient(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                HttpClient.newHttpClient(), new ObjectMapper(), username, password);

        assertThat(client.registerJsonSchema("banco.conta.eventos.v1-value", IntegrationEventSchema.V1))
                .isEqualTo(42);
        assertThat(requestPath.get()).isEqualTo("/subjects/banco.conta.eventos.v1-value/versions");
        assertThat(authorization.get()).isEqualTo("Basic " + Base64.getEncoder().encodeToString(
                (username + ":" + password).getBytes(StandardCharsets.UTF_8)));
        assertThat(new ObjectMapper().readTree(body.get()).path("schemaType").asText()).isEqualTo("JSON");
    }

    @Test
    void schemaIncompativelFazPublicacaoFalharExplicitamente() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] response = "{\"message\":\"Incompatible schema\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(409, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        var client = new SchemaRegistryClient(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                HttpClient.newHttpClient(), new ObjectMapper(), "", "");

        assertThatThrownBy(() -> client.registerJsonSchema("banco.conta.eventos.v1-value",
                IntegrationEventSchema.V1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("HTTP 409")
                .hasMessageContaining("Incompatible schema");
    }
}
