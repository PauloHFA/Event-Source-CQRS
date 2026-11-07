package br.com.banco.api.query;

import br.com.banco.infrastructure.projections.ContaReadModelQueries;
import br.com.banco.infrastructure.projections.ContaSaldoReadModel;
import br.com.banco.infrastructure.projections.ExtratoReadModel;
import br.com.banco.infrastructure.projections.MovimentacaoReadModel;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ContaQueryController.class)
@Import(SecurityConfig.class)
class ContaQueryControllerTest {

    @Autowired MockMvc mvc;
    @MockBean ContaReadModelQueries queries;
    @MockBean ContaOwnershipAuthorization ownership;
    @MockBean JwtDecoder jwtDecoder;

    @TestConfiguration
    static class ObservationTestConfig {
        @Bean
        io.micrometer.observation.ObservationRegistry observationRegistry() {
            return io.micrometer.observation.ObservationRegistry.create();
        }
    }

    @Test
    void saldoExigeAutenticacao() throws Exception {
        mvc.perform(get("/contas/{id}/saldo", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void detalhesRetornaModeloCompletoDaConta() throws Exception {
        UUID accountId = UUID.randomUUID();
        when(queries.saldo(accountId)).thenReturn(Optional.of(new ContaSaldoReadModel(
                accountId, new BigDecimal("125.00"), new BigDecimal("50.00"), new BigDecimal("1000.00"),
                "ATIVA", 4, Instant.parse("2026-01-01T10:00:00Z"))));

        mvc.perform(get("/contas/{id}", accountId)
                        .with(jwt().jwt(token -> token.subject("alice"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contaId").value(accountId.toString()))
                .andExpect(jsonPath("$.saldo").value(125.0))
                .andExpect(jsonPath("$.limite").value(50.0))
                .andExpect(jsonPath("$.limiteDiario").value(1000.0))
                .andExpect(jsonPath("$.status").value("ATIVA"))
                .andExpect(jsonPath("$.versao").value(4));

        verify(ownership).requireOwner(accountId, "alice");
    }

    @Test
    void retornaSaldoProjetadoEAutorizaPeloSubject() throws Exception {
        UUID accountId = UUID.randomUUID();
        when(queries.saldo(accountId)).thenReturn(Optional.of(new ContaSaldoReadModel(
                accountId, new BigDecimal("125.00"), new BigDecimal("50.00"),
                "ATIVA", 4, Instant.parse("2026-01-01T10:00:00Z"))));

        mvc.perform(get("/contas/{id}/saldo", accountId)
                        .with(jwt().jwt(token -> token.subject("alice"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contaId").value(accountId.toString()))
                .andExpect(jsonPath("$.saldo").value(125.0))
                .andExpect(jsonPath("$.versao").value(4));

        verify(ownership).requireOwner(accountId, "alice");
    }

    @Test
    void titularNaoAutorizadoNaoConsultaReadModel() throws Exception {
        UUID accountId = UUID.randomUUID();
        doThrow(new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.NOT_FOUND))
                .when(ownership).requireOwner(accountId, "mallory");

        mvc.perform(get("/contas/{id}/saldo", accountId)
                        .with(jwt().jwt(token -> token.subject("mallory"))))
                .andExpect(status().isNotFound());

        verifyNoInteractions(queries);
    }

    @Test
    void retorna503QuandoSaldoAindaNaoProjetado() throws Exception {
        UUID accountId = UUID.randomUUID();
        when(queries.saldo(accountId)).thenReturn(Optional.empty());

        mvc.perform(get("/contas/{id}/saldo", accountId)
                        .with(jwt().jwt(token -> token.subject("alice"))))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void retornaExtratoPaginado() throws Exception {
        UUID accountId = UUID.randomUUID();
        when(queries.saldo(accountId)).thenReturn(Optional.of(new ContaSaldoReadModel(
                accountId, BigDecimal.TEN, BigDecimal.ZERO, "ATIVA", 2, Instant.EPOCH)));
        when(queries.extrato(eq(accountId), eq(0), eq(10), eq(null), eq(null)))
                .thenReturn(new ExtratoReadModel(
                        accountId, 0, 10, 1L,
                        List.of(new MovimentacaoReadModel(UUID.randomUUID(), "DEPOSITO",
                                BigDecimal.TEN, BigDecimal.TEN, Instant.parse("2026-01-01T10:00:00Z")))));

        mvc.perform(get("/contas/{id}/extrato?pagina=0&tamanho=10", accountId)
                        .with(jwt().jwt(token -> token.subject("alice"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contaId").value(accountId.toString()))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.movimentacoes[0].tipo").value("DEPOSITO"));
    }

    @Test
    void retornaExtratoComFiltroDePeriodo() throws Exception {
        UUID accountId = UUID.randomUUID();
        Instant de = Instant.parse("2026-01-01T00:00:00Z");
        Instant ate = Instant.parse("2026-01-02T00:00:00Z");

        when(queries.saldo(accountId)).thenReturn(Optional.of(new ContaSaldoReadModel(
                accountId, BigDecimal.TEN, BigDecimal.ZERO, "ATIVA", 2, Instant.EPOCH)));
        when(queries.extrato(eq(accountId), eq(0), eq(20), eq(de), eq(ate)))
                .thenReturn(new ExtratoReadModel(
                        accountId, 0, 20, 1L,
                        List.of(new MovimentacaoReadModel(UUID.randomUUID(), "DEPOSITO",
                                BigDecimal.TEN, BigDecimal.TEN, Instant.parse("2026-01-01T10:00:00Z")))));

        mvc.perform(get("/contas/{id}/extrato?de=2026-01-01T00:00:00Z&ate=2026-01-02T00:00:00Z", accountId)
                        .with(jwt().jwt(token -> token.subject("alice"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contaId").value(accountId.toString()))
                .andExpect(jsonPath("$.total").value(1));
    }
}
