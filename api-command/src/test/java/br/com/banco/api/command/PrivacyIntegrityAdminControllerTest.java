package br.com.banco.api.command;

import br.com.banco.infrastructure.eventstore.IntegrityVerification;
import br.com.banco.infrastructure.privacy.CryptoShreddingService;
import br.com.banco.infrastructure.privacy.PersonalDataMasterKeyRotationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PrivacyIntegrityAdminController.class)
@Import({SecurityConfig.class, PrivacyIntegrityAdminControllerTest.ObservationTestConfig.class})
class PrivacyIntegrityAdminControllerTest {

    @Autowired MockMvc mvc;
    @MockBean PrivacyIntegrityAdminService operations;
    @MockBean JwtDecoder jwtDecoder;

    @TestConfiguration
    static class ObservationTestConfig {
        @Bean
        io.micrometer.observation.ObservationRegistry observationRegistry() {
            return io.micrometer.observation.ObservationRegistry.create();
        }
    }

    @Test
    void bloqueiaOperadorSemScopeDePrivacidade() throws Exception {
        mvc.perform(post("/admin/integrity/verify")
                        .with(jwt().jwt(token -> token.subject("operator")))
                        .contentType("application/json")
                        .content("""
                                {"reason":"verificação programada"}
                                """))
                .andExpect(status().isForbidden());

        verifyNoInteractions(operations);
    }

    @Test
    void permiteVerificacaoSomenteComScopeERegistraOperadorAutenticado() throws Exception {
        when(operations.verifyIntegrity("operator", "verificação programada"))
                .thenReturn(new IntegrityVerification(true, 12, 0));

        mvc.perform(post("/admin/integrity/verify")
                        .with(jwt().jwt(token -> token.subject("operator")
                                .claim("scope", "privacy:manage")))
                        .contentType("application/json")
                        .content("""
                                {"reason":"verificação programada"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.verifiedEvents").value(12));

        verify(operations).verifyIntegrity("operator", "verificação programada");
    }

    @Test
    void exigeConfirmacaoExplicitaAntesDeDestruirChave() throws Exception {
        UUID accountId = UUID.randomUUID();

        mvc.perform(post("/admin/privacy/accounts/{accountId}/shred", accountId)
                        .with(jwt().jwt(token -> token.subject("operator")
                                .claim("scope", "privacy:manage")))
                        .contentType("application/json")
                        .content("""
                                {"reason":"pedido aprovado","confirmation":"no"}
                                """))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(operations);
    }

    @Test
    void confirmaOperadorEJustificativaNaOperacaoDeCryptoShredding() throws Exception {
        UUID accountId = UUID.randomUUID();
        when(operations.shredAccountKey(accountId, "operator", "pedido aprovado",
                CryptoShreddingService.CONFIRMATION))
                .thenReturn(new PrivacyIntegrityAdminService.ShreddingResult(accountId, true, "KEY_DESTROYED"));

        mvc.perform(post("/admin/privacy/accounts/{accountId}/shred", accountId)
                        .with(jwt().jwt(token -> token.subject("operator")
                                .claim("scope", "privacy:manage")))
                        .contentType("application/json")
                        .content("""
                                {"reason":"pedido aprovado","confirmation":"DESTROY_PERSONAL_DATA_KEY"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.keyDestroyed").value(true));

        verify(operations).shredAccountKey(accountId, "operator", "pedido aprovado",
                CryptoShreddingService.CONFIRMATION);
    }
}
