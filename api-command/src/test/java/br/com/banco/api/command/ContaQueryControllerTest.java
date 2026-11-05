package br.com.banco.api.command;

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
    void retornaPaginaDoExtratoComOrdenacaoProvenienteDoReadModel() throws Exception {
        UUID accountId = UUID.randomUUID();
        when(queries.saldo(accountId)).thenReturn(Optional.of(new ContaSaldoReadModel(
                accountId, new BigDecimal("12.00"), BigDecimal.ZERO,
                "ATIVA", 2, Instant.parse("2026-01-01T10:00:00Z"))));
        when(queries.extrato(accountId, 1, 5)).thenReturn(new ExtratoReadModel(accountId, 1, 5, 6,
                List.of(new MovimentacaoReadModel(UUID.randomUUID(), "DEPOSITO",
                        new BigDecimal("12.00"), new BigDecimal("12.00"),
                        Instant.parse("2026-01-01T10:00:00Z")))));

        mvc.perform(get("/contas/{id}/extrato", accountId)
                        .param("pagina", "1")
                        .param("tamanho", "5")
                        .with(jwt().jwt(token -> token.subject("alice"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pagina").value(1))
                .andExpect(jsonPath("$.tamanho").value(5))
                .andExpect(jsonPath("$.total").value(6))
                .andExpect(jsonPath("$.movimentacoes[0].tipo").value("DEPOSITO"));

        verify(queries).extrato(eq(accountId), eq(1), eq(5));
    }

    @Test
    void rejeitaTamanhoDePaginaAcimaDoLimite() throws Exception {
        UUID accountId = UUID.randomUUID();

        mvc.perform(get("/contas/{id}/extrato", accountId)
                        .param("tamanho", "101")
                        .with(jwt().jwt(token -> token.subject("alice"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void retornaServicoIndisponivelEnquantoProjecaoAindaNaoExiste() throws Exception {
        UUID accountId = UUID.randomUUID();
        when(queries.saldo(accountId)).thenReturn(Optional.empty());

        mvc.perform(get("/contas/{id}/saldo", accountId)
                        .with(jwt().jwt(token -> token.subject("alice"))))
                .andExpect(status().isServiceUnavailable());
    }
}
