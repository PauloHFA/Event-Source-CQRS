package br.com.banco.api.command;

import br.com.banco.infrastructure.eventstore.IntegrityVerification;
import br.com.banco.infrastructure.eventstore.JdbcEventIntegrityVerifier;
import br.com.banco.infrastructure.privacy.CryptoShreddingService;
import br.com.banco.infrastructure.privacy.JdbcOperationalAuditLog;
import br.com.banco.infrastructure.privacy.PersonalDataMasterKeyRotationService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@Service
public class PrivacyIntegrityAdminService {

    private final CryptoShreddingService cryptoShredding;
    private final PersonalDataMasterKeyRotationService keyRotation;
    private final JdbcEventIntegrityVerifier integrityVerifier;
    private final JdbcOperationalAuditLog auditLog;
    private final TransactionTemplate verificationTransaction;
    private final String rotationMasterKey;
    private final boolean maintenanceMode;

    public PrivacyIntegrityAdminService(
            CryptoShreddingService cryptoShredding,
            PersonalDataMasterKeyRotationService keyRotation,
            JdbcEventIntegrityVerifier integrityVerifier,
            JdbcOperationalAuditLog auditLog,
            PlatformTransactionManager transactionManager,
            @Value("${banco.crypto.rotation-new-master-key:}") String rotationMasterKey,
            @Value("${banco.maintenance.enabled:false}") boolean maintenanceMode) {
        this.cryptoShredding = cryptoShredding;
        this.keyRotation = keyRotation;
        this.integrityVerifier = integrityVerifier;
        this.auditLog = auditLog;
        this.rotationMasterKey = rotationMasterKey;
        this.maintenanceMode = maintenanceMode;
        this.verificationTransaction = new TransactionTemplate(transactionManager);
        this.verificationTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    public ShreddingResult shredAccountKey(UUID accountId, String operator, String reason, String confirmation) {
        boolean destroyed = cryptoShredding.destroyAccountKey(accountId, operator, reason, confirmation);
        return new ShreddingResult(accountId, destroyed,
                destroyed ? "KEY_DESTROYED" : "KEY_NOT_FOUND");
    }

    public IntegrityVerification verifyIntegrity(String operator, String reason) {
        IntegrityVerification verification = verificationTransaction.execute(status -> {
            IntegrityVerification result = integrityVerifier.verifyInCurrentTransaction();
            auditLog.record("VERIFY_EVENT_INTEGRITY", null, operator, reason,
                    result.valid() ? "VALID" : "INVALID",
                    result.verifiedEvents(), result.valid() ? null : result.firstInvalidPosition());
            return result;
        });
        if (verification == null) {
            throw new IllegalStateException("A verificação de integridade não retornou resultado.");
        }
        return verification;
    }

    public MasterKeyRotationResult rotateMasterKey(String operator, String reason, String confirmation) {
        if (!maintenanceMode) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Ative o modo de manutenção antes de rotacionar a chave mestra.");
        }
        if (rotationMasterKey == null || rotationMasterKey.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "A chave mestra de destino não está configurada.");
        }
        int rewrapped = keyRotation.rotate(rotationMasterKey, operator, reason, confirmation);
        return new MasterKeyRotationResult(rewrapped, "KEYS_REWRAPPED");
    }

    public record ShreddingResult(UUID accountId, boolean keyDestroyed, String outcome) {}
    public record MasterKeyRotationResult(int rewrappedKeys, String outcome) {}
}
