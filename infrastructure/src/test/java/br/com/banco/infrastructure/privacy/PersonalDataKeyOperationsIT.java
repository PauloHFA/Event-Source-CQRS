package br.com.banco.infrastructure.privacy;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class PersonalDataKeyOperationsIT {

    private static final String OLD_MASTER_KEY = Base64.getEncoder().encodeToString(new byte[32]);
    private static final String NEW_MASTER_KEY = Base64.getEncoder().encodeToString(new byte[]{1, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0});

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static HikariDataSource dataSource;
    static JdbcTemplate jdbc;
    static TransactionTemplate transaction;
    static JdbcOperationalAuditLog auditLog;

    @BeforeAll
    static void setUp() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(POSTGRES.getJdbcUrl());
        config.setUsername(POSTGRES.getUsername());
        config.setPassword(POSTGRES.getPassword());
        dataSource = new HikariDataSource(config);
        Flyway.configure().dataSource(dataSource).load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        auditLog = new JdbcOperationalAuditLog(jdbc);
    }

    @AfterAll
    static void tearDown() {
        if (dataSource != null) dataSource.close();
    }

    @Test
    void cryptoShreddingAuditaResultadoNaMesmaTransacao() {
        UUID accountId = UUID.randomUUID();
        var keyStore = new JdbcPersonalDataKeyStore(jdbc, OLD_MASTER_KEY);
        var cipher = new AesGcmPersonalDataCipher(keyStore);
        String encrypted = cipher.encrypt(accountId, "titularRef", "owner-ref");
        var service = new CryptoShreddingService(cipher, transaction, auditLog);

        assertThat(service.destroyAccountKey(accountId, "privacy-operator", "pedido aprovado",
                CryptoShreddingService.CONFIRMATION)).isTrue();
        assertThat(cipher.decrypt(accountId, "titularRef", encrypted)).isNull();
        assertThat(jdbc.queryForObject("""
                SELECT outcome FROM privacy_integrity_audit
                WHERE operation = 'CRYPTO_SHRED_ACCOUNT_KEY' AND account_id = ?
                """, String.class, accountId)).isEqualTo("KEY_DESTROYED");
    }

    @Test
    void falhaDeAuditoriaReverteShreddingERotacao() {
        UUID accountId = UUID.randomUUID();
        var keyStore = new JdbcPersonalDataKeyStore(jdbc, OLD_MASTER_KEY);
        var cipher = new AesGcmPersonalDataCipher(keyStore);
        String encrypted = cipher.encrypt(accountId, "titularRef", "owner-ref");
        var shredding = new CryptoShreddingService(cipher, transaction, auditLog);
        var rotation = new PersonalDataMasterKeyRotationService(jdbc, keyStore, transaction, auditLog);

        assertThatThrownBy(() -> shredding.destroyAccountKey(accountId, "privacy-operator", " ",
                CryptoShreddingService.CONFIRMATION))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(cipher.decrypt(accountId, "titularRef", encrypted)).isEqualTo("owner-ref");

        assertThatThrownBy(() -> rotation.rotate(NEW_MASTER_KEY, "privacy-operator", " ",
                PersonalDataMasterKeyRotationService.CONFIRMATION))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(cipher.decrypt(accountId, "titularRef", encrypted)).isEqualTo("owner-ref");
        assertThat(new JdbcPersonalDataKeyStore(jdbc, OLD_MASTER_KEY).find(accountId)).isPresent();
    }

    @Test
    void rotacaoReprotegeTodasAsChavesEPermiteLeituraComNovaChave() {
        UUID accountId = UUID.randomUUID();
        var keyStore = new JdbcPersonalDataKeyStore(jdbc, OLD_MASTER_KEY);
        var cipher = new AesGcmPersonalDataCipher(keyStore);
        String encrypted = cipher.encrypt(accountId, "titularRef", "owner-ref");
        var service = new PersonalDataMasterKeyRotationService(jdbc, keyStore, transaction, auditLog);

        assertThat(service.rotate(NEW_MASTER_KEY, "privacy-operator", "rotacao planejada",
                PersonalDataMasterKeyRotationService.CONFIRMATION)).isEqualTo(1);
        assertThat(cipher.decrypt(accountId, "titularRef", encrypted)).isEqualTo("owner-ref");

        var staleKeyStore = new JdbcPersonalDataKeyStore(jdbc, OLD_MASTER_KEY);
        assertThatThrownBy(() -> staleKeyStore.find(accountId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("proteger a chave");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM privacy_integrity_audit
                WHERE operation = 'ROTATE_MASTER_KEY' AND outcome = 'KEYS_REWRAPPED'
                """, Integer.class)).isEqualTo(1);
    }
}
