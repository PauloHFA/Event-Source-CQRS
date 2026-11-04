package br.com.banco.infrastructure.privacy;

import org.springframework.jdbc.core.JdbcTemplate;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Stores per-account data keys encrypted under an externally supplied 256-bit master key. */
public final class JdbcPersonalDataKeyStore implements PersonalDataKeyStore {

    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private final JdbcTemplate jdbc;
    private final byte[] masterKey;
    private final SecureRandom random = new SecureRandom();

    public JdbcPersonalDataKeyStore(JdbcTemplate jdbc, String masterKeyBase64) {
        this.jdbc = jdbc;
        try {
            this.masterKey = Base64.getDecoder().decode(masterKeyBase64);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IllegalArgumentException("banco.crypto.master-key deve ser uma chave Base64 válida.", e);
        }
        if (masterKey.length != 32) {
            throw new IllegalArgumentException("banco.crypto.master-key deve conter exatamente 32 bytes (AES-256).");
        }
    }

    @Override
    public byte[] getOrCreate(UUID accountId) {
        List<byte[]> existing = jdbc.query("""
                SELECT wrapped_key FROM personal_data_keys WHERE account_id = ?
                """, (rs, row) -> rs.getBytes(1), accountId);
        if (!existing.isEmpty()) return unwrap(accountId, existing.getFirst());

        byte[] dataKey = new byte[32];
        random.nextBytes(dataKey);
        jdbc.update("""
                INSERT INTO personal_data_keys (account_id, wrapped_key) VALUES (?, ?)
                ON CONFLICT (account_id) DO NOTHING
                """, accountId, wrap(accountId, dataKey));
        List<byte[]> stored = jdbc.query("""
                SELECT wrapped_key FROM personal_data_keys WHERE account_id = ?
                """, (rs, row) -> rs.getBytes(1), accountId);
        if (stored.isEmpty()) throw new IllegalStateException("Não foi possível persistir a chave de " + accountId);
        return unwrap(accountId, stored.getFirst());
    }

    @Override
    public Optional<byte[]> find(UUID accountId) {
        List<byte[]> wrapped = jdbc.query("""
                SELECT wrapped_key FROM personal_data_keys WHERE account_id = ?
                """, (rs, row) -> rs.getBytes(1), accountId);
        return wrapped.stream().findFirst().map(value -> unwrap(accountId, value));
    }

    @Override
    public boolean destroy(UUID accountId) {
        return jdbc.update("DELETE FROM personal_data_keys WHERE account_id = ?", accountId) == 1;
    }

    private byte[] wrap(UUID accountId, byte[] dataKey) {
        byte[] iv = new byte[IV_BYTES];
        random.nextBytes(iv);
        byte[] encrypted = crypt(Cipher.ENCRYPT_MODE, masterKey, accountId, iv, dataKey);
        return ByteBuffer.allocate(iv.length + encrypted.length).put(iv).put(encrypted).array();
    }

    private byte[] unwrap(UUID accountId, byte[] envelope) {
        if (envelope.length <= IV_BYTES) throw new IllegalStateException("Chave envelopada truncada: " + accountId);
        byte[] iv = new byte[IV_BYTES];
        byte[] ciphertext = new byte[envelope.length - IV_BYTES];
        System.arraycopy(envelope, 0, iv, 0, IV_BYTES);
        System.arraycopy(envelope, IV_BYTES, ciphertext, 0, ciphertext.length);
        return crypt(Cipher.DECRYPT_MODE, masterKey, accountId, iv, ciphertext);
    }

    private byte[] crypt(int mode, byte[] key, UUID accountId, byte[] iv, byte[] input) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(("personal-data-key:" + accountId).getBytes(StandardCharsets.UTF_8));
            return cipher.doFinal(input);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Falha ao proteger a chave de dados de " + accountId, e);
        }
    }
}
