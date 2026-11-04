package br.com.banco.infrastructure.privacy;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

/** Field-level AES-GCM encryption. The per-account key never leaves the key-store boundary. */
public final class AesGcmPersonalDataCipher {

    public static final String PREFIX = "enc:v1:";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final PersonalDataKeyStore keys;
    private final SecureRandom random;

    public AesGcmPersonalDataCipher(PersonalDataKeyStore keys) {
        this(keys, new SecureRandom());
    }

    AesGcmPersonalDataCipher(PersonalDataKeyStore keys, SecureRandom random) {
        this.keys = keys;
        this.random = random;
    }

    public String encrypt(UUID accountId, String field, String plaintext) {
        if (plaintext == null) return null;
        byte[] iv = new byte[IV_BYTES];
        random.nextBytes(iv);
        byte[] ciphertext = crypt(Cipher.ENCRYPT_MODE, keys.getOrCreate(accountId), accountId, field,
                iv, plaintext.getBytes(StandardCharsets.UTF_8));
        byte[] envelope = new byte[iv.length + ciphertext.length];
        System.arraycopy(iv, 0, envelope, 0, iv.length);
        System.arraycopy(ciphertext, 0, envelope, iv.length, ciphertext.length);
        return PREFIX + Base64.getEncoder().encodeToString(envelope);
    }

    /** Returns null after key destruction; plaintext legacy values remain readable until migrated. */
    public String decrypt(UUID accountId, String field, String value) {
        if (value == null || !value.startsWith(PREFIX)) return value;
        Optional<byte[]> key = keys.find(accountId);
        if (key.isEmpty()) return null;
        byte[] envelope;
        try {
            envelope = Base64.getDecoder().decode(value.substring(PREFIX.length()));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Envelope cifrado inválido para " + field + ".", e);
        }
        if (envelope.length <= IV_BYTES) {
            throw new IllegalStateException("Envelope cifrado truncado para " + field + ".");
        }
        byte[] iv = new byte[IV_BYTES];
        byte[] ciphertext = new byte[envelope.length - IV_BYTES];
        System.arraycopy(envelope, 0, iv, 0, IV_BYTES);
        System.arraycopy(envelope, IV_BYTES, ciphertext, 0, ciphertext.length);
        return new String(crypt(Cipher.DECRYPT_MODE, key.get(), accountId, field, iv, ciphertext),
                StandardCharsets.UTF_8);
    }

    public boolean destroy(UUID accountId) {
        return keys.destroy(accountId);
    }

    private byte[] crypt(int mode, byte[] key, UUID accountId, String field, byte[] iv, byte[] input) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD((accountId + ":" + field).getBytes(StandardCharsets.UTF_8));
            return cipher.doFinal(input);
        } catch (AEADBadTagException e) {
            throw new IllegalStateException("A autenticação dos dados cifrados falhou para " + field + ".", e);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Falha na operação AES-GCM de " + field + ".", e);
        }
    }
}
