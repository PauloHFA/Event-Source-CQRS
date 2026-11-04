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

import static org.assertj.core.api.Assertions.assertThat;

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

        var client = new SchemaRegistryClient(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                HttpClient.newHttpClient(), new ObjectMapper(), "registry-user", "secret");

        assertThat(client.registerJsonSchema("banco.conta.eventos.v1-value", IntegrationEventSchema.V1))
                .isEqualTo(42);
        assertThat(requestPath.get()).isEqualTo("/subjects/banco.conta.eventos.v1-value/versions");
        assertThat(authorization.get()).isEqualTo("Basic cmVnaXN0cnktdXNlcjpzZWNyZXQ=");
        assertThat(new ObjectMapper().readTree(body.get()).path("schemaType").asText()).isEqualTo("JSON");
    }
}
