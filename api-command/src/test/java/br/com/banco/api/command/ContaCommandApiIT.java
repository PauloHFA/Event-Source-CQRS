package br.com.banco.api.command;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ContaCommandApiIT {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", PG::getJdbcUrl);
        r.add("spring.datasource.username", PG::getUsername);
        r.add("spring.datasource.password", PG::getPassword);
    }

    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;

    private ResponseEntity<String> post(String url, Object body, String key) {
        var h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        if (key != null) h.set("Idempotency-Key", key);
        return rest.postForEntity(url, new HttpEntity<>(body, h), String.class);
    }

    private String abrirConta(String key) throws Exception {
        var r = post("/contas", Map.of("titularRef", "t-1", "limite", "0"), key);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return mapper.readTree(r.getBody()).get("contaId").asText();
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
}

