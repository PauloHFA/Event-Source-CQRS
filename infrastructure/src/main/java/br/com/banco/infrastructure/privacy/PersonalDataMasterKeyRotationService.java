package br.com.banco.infrastructure.privacy;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Arrays;

public final class PersonalDataMasterKeyRotationService {
    public static final String CONFIRMATION = "ROTATE_MASTER_KEY";

    private final JdbcTemplate jdbc;
    private final JdbcPersonalDataKeyStore keyStore;
    private final TransactionTemplate transaction;
    private final JdbcOperationalAuditLog auditLog;

    public PersonalDataMasterKeyRotationService(JdbcTemplate jdbc, JdbcPersonalDataKeyStore keyStore,
                                                TransactionTemplate transaction,
                                                JdbcOperationalAuditLog auditLog) {
        this.jdbc = jdbc;
        this.keyStore = keyStore;
        this.transaction = transaction;
        this.auditLog = auditLog;
    }

    public synchronized int rotate(String newMasterKeyBase64, String operator, String reason,
                                   String confirmation) {
        if (!CONFIRMATION.equals(confirmation)) {
            throw new IllegalArgumentException("Confirmação explícita da rotação de chave inválida.");
        }
        byte[] newMasterKey = JdbcPersonalDataKeyStore.decodeMasterKey(newMasterKeyBase64);
        try {
            synchronized (keyStore) {
                Integer rewrapped = transaction.execute(status -> {
                    jdbc.execute("LOCK TABLE personal_data_keys IN EXCLUSIVE MODE");
                    int count = keyStore.rewrapAll(newMasterKey);
                    auditLog.record("ROTATE_MASTER_KEY", null, operator, reason,
                            "KEYS_REWRAPPED", (long) count, null);
                    return count;
                });
                if (rewrapped == null) {
                    throw new IllegalStateException("A rotação da chave mestra não retornou resultado.");
                }
                keyStore.activateMasterKey(newMasterKey);
                return rewrapped;
            }
        } finally {
            Arrays.fill(newMasterKey, (byte) 0);
        }
    }
}
