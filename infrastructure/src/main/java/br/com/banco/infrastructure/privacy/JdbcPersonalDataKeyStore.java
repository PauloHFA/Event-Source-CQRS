package br.com.banco.infrastructure.privacy;

import org.springframework.jdbc.core.JdbcTemplate;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Stores per-account data keys encrypted under an externally supplied 256-bit master key. */
public final class JdbcPersonalDataKeyStore implements PersonalDataKeyStore {

    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private final JdbcTemplate jdbc;
    private volatile byte[] masterKey;
    private final SecureRandom random = new SecureRandom();

    public JdbcPersonalDataKeyStore(JdbcTemplate jdbc, String masterKeyBase64) {
        this.jdbc = jdbc;
        this.masterKey = decodeMasterKey(masterKeyBase64);
    }

    @Override
    public synchronized byte[] getOrCreate(UUID accountId) {
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
    public synchronized Optional<byte[]> find(UUID accountId) {
        List<byte[]> wrapped = jdbc.query("""
                SELECT wrapped_key FROM personal_data_keys WHERE account_id = ?
                """, (rs, row) -> rs.getBytes(1), accountId);
        return wrapped.stream().findFirst().map(value -> unwrap(accountId, value));
    }

    @Override
    public synchronized boolean destroy(UUID accountId) {
        return jdbc.update("DELETE FROM personal_data_keys WHERE account_id = ?", accountId) == 1;
    }

    synchronized int rewrapAll(byte[] newMasterKey) {
        List<KeyEnvelope> keys = jdbc.query("""
                        SELECT account_id, wrapped_key
                        FROM personal_data_keys
                        ORDER BY account_id
                        FOR UPDATE
                        """,
                (rs, row) -> new KeyEnvelope(rs.getObject("account_id", UUID.class),
                        rs.getBytes("wrapped_key")));
        for (KeyEnvelope envelope : keys) {
            byte[] dataKey = unwrap(envelope.accountId, envelope.wrappedKey, masterKey);
            try {
                jdbc.update("UPDATE personal_data_keys SET wrapped_key = ? WHERE account_id = ?",
                        wrap(envelope.accountId, dataKey, newMasterKey), envelope.accountId);
            } finally {
                Arrays.fill(dataKey, (byte) 0);
            }
        }
        return keys.size();
    }

    synchronized void activateMasterKey(byte[] newMasterKey) {
        byte[] previousMasterKey = this.masterKey;
        this.masterKey = newMasterKey.clone();
        Arrays.fill(previousMasterKey, (byte) 0);
    }

    private byte[] wrap(UUID accountId, byte[] dataKey) {
        return wrap(accountId, dataKey, masterKey);
    }

    private byte[] wrap(UUID accountId, byte[] dataKey, byte[] key) {
        byte[] iv = new byte[IV_BYTES];
        random.nextBytes(iv);
        byte[] encrypted = crypt(Cipher.ENCRYPT_MODE, key, accountId, iv, dataKey);
        return ByteBuffer.allocate(iv.length + encrypted.length).put(iv).put(encrypted).array();
    }

    private byte[] unwrap(UUID accountId, byte[] envelope) {
        return unwrap(accountId, envelope, masterKey);
    }

    private byte[] unwrap(UUID accountId, byte[] envelope, byte[] key) {
        if (envelope.length <= IV_BYTES) throw new IllegalStateException("Chave envelopada truncada: " + accountId);
        byte[] iv = new byte[IV_BYTES];
        byte[] ciphertext = new byte[envelope.length - IV_BYTES];
        System.arraycopy(envelope, 0, iv, 0, IV_BYTES);
        System.arraycopy(envelope, IV_BYTES, ciphertext, 0, ciphertext.length);
        return crypt(Cipher.DECRYPT_MODE, key, accountId, iv, ciphertext);
    }

    static byte[] decodeMasterKey(String encoded) {
        byte[] key;
        try {
            key = Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IllegalArgumentException("A chave mestra deve ser uma chave Base64 válida.", e);
        }
        if (key.length != 32) {
            Arrays.fill(key, (byte) 0);
            throw new IllegalArgumentException("A chave mestra deve conter exatamente 32 bytes (AES-256).");
        }
        return key;
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

    private record KeyEnvelope(UUID accountId, byte[] wrappedKey) {}
}
