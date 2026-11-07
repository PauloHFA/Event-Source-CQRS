package br.com.banco.api.command;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import br.com.banco.infrastructure.eventstore.IntegrityVerification;
import br.com.banco.infrastructure.privacy.CryptoShreddingService;
import br.com.banco.infrastructure.privacy.PersonalDataMasterKeyRotationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@Tag(name = "Administração (Privacidade e Integridade)", description = "Operações restritas: Crypto-shredding (LGPD), Rotação de chaves e Auditoria SHA-256")
@RestController
@RequestMapping("/admin")
public class PrivacyIntegrityAdminController {

    public record DestructionRequest(
            @NotBlank @Size(max = 500) String reason,
            @NotBlank @Pattern(regexp = "DESTROY_PERSONAL_DATA_KEY") String confirmation) {}

    public record RotationRequest(
            @NotBlank @Size(max = 500) String reason,
            @NotBlank @Pattern(regexp = "ROTATE_MASTER_KEY") String confirmation) {}

    public record VerificationRequest(@NotBlank @Size(max = 500) String reason) {}

    private final PrivacyIntegrityAdminService operations;

    public PrivacyIntegrityAdminController(PrivacyIntegrityAdminService operations) {
        this.operations = operations;
    }

    @Operation(summary = "Executar crypto-shredding da conta", description = "Destrói a chave simétrica de titular (direito ao esquecimento - LGPD).")
    @PostMapping("/privacy/accounts/{accountId}/shred")
    public PrivacyIntegrityAdminService.ShreddingResult shredAccountKey(
            @PathVariable UUID accountId, @Valid @RequestBody DestructionRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return operations.shredAccountKey(accountId, jwt.getSubject(), request.reason(), request.confirmation());
    }

    @Operation(summary = "Rotacionar Master Key", description = "Re-envelopa as chaves de titular com nova chave mestra.")
    @PostMapping("/privacy/master-key/rotate")
    public PrivacyIntegrityAdminService.MasterKeyRotationResult rotateMasterKey(
            @Valid @RequestBody RotationRequest request, @AuthenticationPrincipal Jwt jwt) {
        return operations.rotateMasterKey(jwt.getSubject(), request.reason(), request.confirmation());
    }

    @Operation(summary = "Verificar integridade do stream de eventos", description = "Recalcula e audita a cadeia de hashes SHA-256 por stream.")
    @PostMapping("/integrity/verify")
    public IntegrityVerification verifyIntegrity(
            @Valid @RequestBody VerificationRequest request, @AuthenticationPrincipal Jwt jwt) {
        return operations.verifyIntegrity(jwt.getSubject(), request.reason());
    }
}
