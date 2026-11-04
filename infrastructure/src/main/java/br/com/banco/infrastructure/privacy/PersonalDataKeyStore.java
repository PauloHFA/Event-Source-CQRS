package br.com.banco.infrastructure.privacy;

import java.util.Optional;
import java.util.UUID;

public interface PersonalDataKeyStore {
    byte[] getOrCreate(UUID accountId);

    Optional<byte[]> find(UUID accountId);

    boolean destroy(UUID accountId);
}
