package br.com.banco.api.query;

import br.com.banco.infrastructure.eventstore.EventSerializer;
import br.com.banco.infrastructure.privacy.AesGcmPersonalDataCipher;
import br.com.banco.infrastructure.privacy.JdbcPersonalDataKeyStore;
import br.com.banco.infrastructure.projections.ContaReadModelQueries;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
public class QueryBeansConfig {

    @Bean
    JdbcPersonalDataKeyStore personalDataKeyStore(
            JdbcTemplate jdbc, @Value("${banco.crypto.master-key:}") String masterKey) {
        return new JdbcPersonalDataKeyStore(jdbc, masterKey);
    }

    @Bean
    AesGcmPersonalDataCipher personalDataCipher(JdbcPersonalDataKeyStore keys) {
        return new AesGcmPersonalDataCipher(keys);
    }

    @Bean
    EventSerializer eventSerializer(AesGcmPersonalDataCipher cipher) {
        return new EventSerializer(cipher);
    }

    @Bean
    ContaReadModelQueries contaReadModelQueries(JdbcTemplate jdbc) {
        return new ContaReadModelQueries(jdbc);
    }
}
