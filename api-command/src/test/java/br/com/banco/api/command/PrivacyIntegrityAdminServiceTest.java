package br.com.banco.api.command;

import br.com.banco.infrastructure.eventstore.JdbcEventIntegrityVerifier;
import br.com.banco.infrastructure.privacy.CryptoShreddingService;
import br.com.banco.infrastructure.privacy.JdbcOperationalAuditLog;
import br.com.banco.infrastructure.privacy.PersonalDataMasterKeyRotationService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PrivacyIntegrityAdminServiceTest {

    private final CryptoShreddingService cryptoShredding = mock(CryptoShreddingService.class);
    private final PersonalDataMasterKeyRotationService keyRotation =
            mock(PersonalDataMasterKeyRotationService.class);
    private final JdbcEventIntegrityVerifier integrityVerifier = mock(JdbcEventIntegrityVerifier.class);
    private final JdbcOperationalAuditLog auditLog = mock(JdbcOperationalAuditLog.class);
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);

    @Test
    void bloqueiaRotacaoForaDaJanelaDeManutencao() {
        PrivacyIntegrityAdminService service = service("destination-key", false);

        assertThatThrownBy(() -> service.rotateMasterKey("operator", "planned rotation",
                PersonalDataMasterKeyRotationService.CONFIRMATION))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
                        .isEqualTo(HttpStatus.CONFLICT));

        verifyNoInteractions(keyRotation);
    }

    @Test
    void bloqueiaRotacaoSemChaveDestinoConfigurada() {
        PrivacyIntegrityAdminService service = service("", true);

        assertThatThrownBy(() -> service.rotateMasterKey("operator", "planned rotation",
                PersonalDataMasterKeyRotationService.CONFIRMATION))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
                        .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));

        verifyNoInteractions(keyRotation);
    }

    @Test
    void executaRotacaoComChaveDestinoSomenteEmManutencao() {
        PrivacyIntegrityAdminService service = service("destination-key", true);
        when(keyRotation.rotate("destination-key", "operator", "planned rotation",
                PersonalDataMasterKeyRotationService.CONFIRMATION)).thenReturn(3);

        var result = service.rotateMasterKey("operator", "planned rotation",
                PersonalDataMasterKeyRotationService.CONFIRMATION);

        assertThat(result.rewrappedKeys()).isEqualTo(3);
        verify(keyRotation).rotate("destination-key", "operator", "planned rotation",
                PersonalDataMasterKeyRotationService.CONFIRMATION);
    }

    private PrivacyIntegrityAdminService service(String rotationMasterKey, boolean maintenanceMode) {
        return new PrivacyIntegrityAdminService(cryptoShredding, keyRotation, integrityVerifier,
                auditLog, transactionManager, rotationMasterKey, maintenanceMode);
    }
}
