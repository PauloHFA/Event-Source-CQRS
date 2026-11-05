package br.com.banco.infrastructure.privacy;

import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

public final class CryptoShreddingService {
    public static final String CONFIRMATION = "DESTROY_PERSONAL_DATA_KEY";

    private final AesGcmPersonalDataCipher cipher;
    private final TransactionTemplate transaction;
    private final JdbcOperationalAuditLog auditLog;

    public CryptoShreddingService(AesGcmPersonalDataCipher cipher, TransactionTemplate transaction,
                                  JdbcOperationalAuditLog auditLog) {
        this.cipher = cipher;
        this.transaction = transaction;
        this.auditLog = auditLog;
    }

    /** Removes the wrapped account key and records the operation atomically. */
    public boolean destroyAccountKey(UUID accountId, String operator, String reason, String confirmation) {
        if (!CONFIRMATION.equals(confirmation)) {
            throw new IllegalArgumentException("Confirmação explícita de crypto-shredding inválida.");
        }
        Boolean destroyed = transaction.execute(status -> {
            boolean keyDestroyed = cipher.destroy(accountId);
            auditLog.record("CRYPTO_SHRED_ACCOUNT_KEY", accountId, operator, reason,
                    keyDestroyed ? "KEY_DESTROYED" : "KEY_NOT_FOUND", null, null);
            return keyDestroyed;
        });
        if (destroyed == null) {
            throw new IllegalStateException("A operação de crypto-shredding não retornou resultado.");
        }
        return destroyed;
    }
}
