package br.com.banco.api.command;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;
import java.util.Base64;
import java.util.UUID;
import java.time.Instant;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ContextConfiguration(classes = CommandApiApplication.class)
class ContaCommandApiIT {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", PG::getJdbcUrl);
        r.add("spring.datasource.username", PG::getUsername);
        r.add("spring.datasource.password", PG::getPassword);
        r.add("banco.crypto.master-key", () -> Base64.getEncoder().encodeToString(new byte[32]));
        r.add("spring.security.oauth2.resourceserver.jwt.issuer-uri",
                () -> "http://localhost:9000/realms/banco-test");
    }

    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @MockBean JwtDecoder jwtDecoder;

    private ResponseEntity<String> post(String url, Object body, String key) {
        return postAs("test-owner", url, body, key);
    }

    private ResponseEntity<String> postAs(String subject, String url, Object body, String key) {
        var h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.setBearerAuth("subject:" + subject);
        if (key != null) h.set("Idempotency-Key", key);
        return rest.postForEntity(url, new HttpEntity<>(body, h), String.class);
    }

    @BeforeEach
    void configureJwtDecoder() {
        when(jwtDecoder.decode(anyString())).thenAnswer(invocation -> {
            String token = invocation.getArgument(0);
            return Jwt.withTokenValue(token)
                    .header("alg", "none")
                    .claim("sub", token.substring("subject:".length()))
                    .issuedAt(Instant.now())
                    .expiresAt(Instant.now().plusSeconds(300))
                    .build();
        });
    }

    private String abrirConta(String key) throws Exception {
        var r = post("/contas", Map.of("limite", "0"), key);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return mapper.readTree(r.getBody()).get("contaId").asText();
    }

    @Test
    void endpointProtegidoRejeitaRequisicaoSemToken() {
        var response = rest.postForEntity("/contas",
                new HttpEntity<>(Map.of("limite", "0")), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void titularAutenticadoEObtidoDoTokenEHeaderArbitrarioNaoAutorizaOutroUsuario() throws Exception {
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth("subject:alice");
        headers.set("X-User-Id", "bob");
        var opened = rest.postForEntity("/contas",
                new HttpEntity<>(Map.of("limite", "0"), headers), String.class);
        assertThat(opened.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String accountId = mapper.readTree(opened.getBody()).get("contaId").asText();

        var forged = new HttpHeaders();
        forged.setContentType(MediaType.APPLICATION_JSON);
        forged.setBearerAuth("subject:bob");
        forged.set("X-User-Id", "alice");
        var denied = rest.postForEntity("/contas/" + accountId + "/depositos",
                new HttpEntity<>(Map.of("valor", "10", "origem", "CAIXA"), forged), String.class);

        assertThat(denied.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        String owner = jdbc.queryForObject(
                "SELECT metadata->>'userId' FROM events WHERE stream_id = ? AND version = 1",
                String.class, "conta-" + accountId);
        assertThat(owner).isEqualTo("alice");
    }

    @Test
    void CA05_depositoComMesmaIdempotencyKeyNaoDuplica() throws Exception {
        String id = abrirConta(null);
        var body = Map.of("valor", "50.00", "origem", "CAIXA");

        var primeira = post("/contas/" + id + "/depositos", body, "K-1");
        var segunda = post("/contas/" + id + "/depositos", body, "K-1");

        assertThat(primeira.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(segunda.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        JsonNode a = mapper.readTree(primeira.getBody());
        JsonNode b = mapper.readTree(segunda.getBody());
        assertThat(b).isEqualTo(a);
        Integer eventos = jdbc.queryForObject(
                "SELECT count(*) FROM events WHERE stream_id = ?", Integer.class, "conta-" + id);
        assertThat(eventos).isEqualTo(2); // ContaAberta + um único ValorDepositado
    }

    @Test
    void CA02_saqueSemSaldoRetorna422() throws Exception {
        String id = abrirConta(null);

        var r = post("/contas/" + id + "/saques", Map.of("valor", "10"), null);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        Integer eventos = jdbc.queryForObject(
                "SELECT count(*) FROM events WHERE stream_id = ?", Integer.class, "conta-" + id);
        assertThat(eventos).isEqualTo(1);
    }

    @Test
    void valorNegativoRetorna400() throws Exception {
        String id = abrirConta(null);

        var r = post("/contas/" + id + "/depositos", Map.of("valor", "-5", "origem", "X"), null);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void correlationIdEPropagadoAoEventoEResposta() throws Exception {
        UUID correlationId = UUID.randomUUID();
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth("subject:test-owner");
        headers.set("X-Correlation-Id", correlationId.toString());

        var response = rest.postForEntity("/contas",
                new HttpEntity<>(Map.of("limite", "0"), headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getHeaders().getFirst("X-Correlation-Id")).isEqualTo(correlationId.toString());
        String accountId = mapper.readTree(response.getBody()).get("contaId").asText();
        String storedCorrelationId = jdbc.queryForObject(
                "SELECT metadata->>'correlationId' FROM events WHERE stream_id = ?",
                String.class, "conta-" + accountId);
        assertThat(storedCorrelationId).isEqualTo(correlationId.toString());
    }

    @Test
    void geraCorrelationIdQuandoClienteNaoEnvia() {
        var response = post("/contas", Map.of("limite", "0"), null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String header = response.getHeaders().getFirst("X-Correlation-Id");
        assertThat(header).isNotBlank();
        assertThat(UUID.fromString(header).toString()).isEqualTo(header);
    }
}
