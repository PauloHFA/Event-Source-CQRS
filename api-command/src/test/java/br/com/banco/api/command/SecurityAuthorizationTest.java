package br.com.banco.api.command;

import br.com.banco.application.CommandResult;
import br.com.banco.application.Commands.AbrirConta;
import br.com.banco.application.Commands.DepositarValor;
import br.com.banco.application.ContaCommandHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest({ContaCommandController.class})
@Import(SecurityConfig.class)
class SecurityAuthorizationTest {

    @Autowired MockMvc mvc;
    @MockBean ContaCommandHandler handler;
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
    void rejeitaComandoSemToken() throws Exception {
        mvc.perform(post("/contas")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"limite": 0}
                                """))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejeitaTokenInvalido() throws Exception {
        when(jwtDecoder.decode("invalid")).thenThrow(new BadJwtException("Token inválido"));

        mvc.perform(post("/contas")
                        .header("Authorization", "Bearer invalid")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"limite": 0}
                                """))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void titularEObtidoDoSubEIgnoraHeaderDeIdentidadeEnviadoPeloCliente() throws Exception {
        UUID accountId = UUID.randomUUID();
        when(handler.handle(any(AbrirConta.class))).thenReturn(new CommandResult(accountId, 1));

        mvc.perform(post("/contas")
                        .with(jwt().jwt(token -> token.subject("alice")))
                        .header("X-User-Id", "mallory")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"limite": 0}
                                """))
                .andExpect(status().isCreated());

        var command = org.mockito.ArgumentCaptor.forClass(AbrirConta.class);
        verify(handler).handle(command.capture());
        assertThat(command.getValue().titularRef()).isEqualTo("alice");
        assertThat(command.getValue().ctx().userId()).isEqualTo("alice");
    }

    @Test
    void comandoDeContaExigeQueSubSejaTitular() throws Exception {
        UUID accountId = UUID.randomUUID();
        when(handler.handle(any(DepositarValor.class))).thenReturn(new CommandResult(accountId, 2));

        mvc.perform(post("/contas/{id}/depositos", accountId)
                        .with(jwt().jwt(token -> token.subject("alice")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"valor": 10, "origem": "CAIXA"}
                                """))
                .andExpect(status().isAccepted());

        verify(ownership).requireOwner(eq(accountId), eq("alice"));
    }

    @Test
    void titularDiferenteNaoExecutaComando() throws Exception {
        UUID accountId = UUID.randomUUID();
        doThrow(new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND))
                .when(ownership).requireOwner(accountId, "mallory");

        mvc.perform(post("/contas/{id}/depositos", accountId)
                        .with(jwt().jwt(token -> token.subject("mallory")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"valor": 10, "origem": "CAIXA"}
                                """))
                .andExpect(status().isNotFound());

        org.mockito.Mockito.verifyNoInteractions(handler);
    }
}
