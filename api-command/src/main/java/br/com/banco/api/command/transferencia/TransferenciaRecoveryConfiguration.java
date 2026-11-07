package br.com.banco.api.command.transferencia;

import br.com.banco.application.TransferenciaSaga;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Duration;

@Configuration
@EnableScheduling
public class TransferenciaRecoveryConfiguration {

    private static final Logger LOGGER = LoggerFactory.getLogger(TransferenciaRecoveryConfiguration.class);

    @Bean
    @ConditionalOnProperty(name = "banco.transferencia.recovery.enabled", havingValue = "true", matchIfMissing = true)
    TransferenciaRecoveryScheduler transferenciaRecoveryScheduler(
            TransferenciaSaga saga,
            MeterRegistry meters,
            @Value("${banco.transferencia.recovery.timeout-seconds:300}") long timeoutSeconds) {
        return new TransferenciaRecoveryScheduler(saga, meters, Duration.ofSeconds(timeoutSeconds));
    }

    public static class TransferenciaRecoveryScheduler {

        private final TransferenciaSaga saga;
        private final Duration timeout;
        private final Counter recoveredCounter;

        public TransferenciaRecoveryScheduler(TransferenciaSaga saga, MeterRegistry meters, Duration timeout) {
            this.saga = saga;
            this.timeout = timeout;
            this.recoveredCounter = meters.counter("transferencia.saga.recovered.total");
        }

        @Scheduled(fixedDelayString = "${banco.transferencia.recovery.poll-interval:60000}")
        public int recoverStuckTransfers() {
            try {
                int recuperadas = saga.recuperarTravadas(timeout);
                if (recuperadas > 0) {
                    recoveredCounter.increment(recuperadas);
                    LOGGER.warn("Recuperadas {} transferências travadas paradas há mais de {}s.",
                            recuperadas, timeout.toSeconds());
                }
                return recuperadas;
            } catch (Exception e) {
                LOGGER.error("Erro ao executar ciclo de recuperação de transferências travadas.", e);
                return 0;
            }
        }
    }
}
