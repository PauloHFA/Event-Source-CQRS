package br.com.banco.infrastructure.privacy;

import br.com.banco.domain.Conta;
import br.com.banco.domain.ContaEvent;
import br.com.banco.infrastructure.eventstore.EventSerializer;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AesGcmPersonalDataCipherTest {

    @Test
    void criptografaTitularEApagaAChaveSemAfetarReplayFinanceiro() {
        var keys = new InMemoryKeys();
        var cipher = new AesGcmPersonalDataCipher(keys);
        var serializer = new EventSerializer(cipher);
        UUID accountId = UUID.randomUUID();
        var opened = new ContaEvent.ContaAberta(UUID.randomUUID(), accountId, "ref-pessoal",
                BigDecimal.ZERO, Instant.parse("2026-01-01T00:00:00Z"));
        String stored = serializer.serializeEvent(opened);

        assertThat(stored).doesNotContain("ref-pessoal");
        var readable = (ContaEvent.ContaAberta) serializer.deserialize(
                "conta-" + accountId, "ContaAberta", 2, stored);
        assertThat(readable.titularRef()).isEqualTo("ref-pessoal");

        assertThat(cipher.destroy(accountId)).isTrue();
        var shredded = (ContaEvent.ContaAberta) serializer.deserialize(
                "conta-" + accountId, "ContaAberta", 2, stored);
        assertThat(shredded.titularRef()).isNull();
        assertThat(Conta.reconstruir(java.util.List.of(shredded)).snapshot().id()).isEqualTo(accountId);
    }

    @Test
    void rejeitaAlteracaoNoTextoCifrado() {
        var cipher = new AesGcmPersonalDataCipher(new InMemoryKeys());
        UUID accountId = UUID.randomUUID();
        String encrypted = cipher.encrypt(accountId, "titularRef", "ref");
        byte[] envelope = Base64.getDecoder().decode(encrypted.substring(AesGcmPersonalDataCipher.PREFIX.length()));
        envelope[envelope.length - 1] ^= 1;
        String tampered = AesGcmPersonalDataCipher.PREFIX + Base64.getEncoder().encodeToString(envelope);

        assertThatThrownBy(() -> cipher.decrypt(accountId, "titularRef", tampered))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("autenticação");
    }

    private static final class InMemoryKeys implements PersonalDataKeyStore {
        private final Map<UUID, byte[]> keys = new HashMap<>();

        @Override
        public byte[] getOrCreate(UUID accountId) {
            return keys.computeIfAbsent(accountId, ignored -> {
                byte[] key = new byte[32];
                new java.security.SecureRandom().nextBytes(key);
                return key;
            }).clone();
        }

        @Override
        public Optional<byte[]> find(UUID accountId) {
            return Optional.ofNullable(keys.get(accountId)).map(byte[]::clone);
        }

        @Override
        public boolean destroy(UUID accountId) {
            return keys.remove(accountId) != null;
        }
    }
}
