package br.com.banco.infrastructure.privacy;

import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

public final class CryptoShreddingService {
    private final AesGcmPersonalDataCipher cipher;
    private final TransactionTemplate transaction;

    public CryptoShreddingService(AesGcmPersonalDataCipher cipher, TransactionTemplate transaction) {
        this.cipher = cipher;
        this.transaction = transaction;
    }

    /** Removes the wrapped account key; events and financial values remain unchanged. */
    public boolean destroyAccountKey(UUID accountId) {
        Boolean destroyed = transaction.execute(status -> cipher.destroy(accountId));
        return Boolean.TRUE.equals(destroyed);
    }
}
