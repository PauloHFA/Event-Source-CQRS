package br.com.banco.api.command.projections;

import br.com.banco.infrastructure.eventstore.JdbcEventStore;
import br.com.banco.infrastructure.projections.ContaProjector;
import br.com.banco.infrastructure.projections.ContaReadModelQueries;
import br.com.banco.infrastructure.projections.ProjectionRunner;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration
@EnableScheduling
public class ProjectionConfiguration {

    @Bean
    ContaProjector contaProjector(JdbcTemplate jdbc) {
        return new ContaProjector(jdbc);
    }

    @Bean
    ContaReadModelQueries contaReadModelQueries(JdbcTemplate jdbc) {
        return new ContaReadModelQueries(jdbc);
    }

    @Bean
    @ConditionalOnProperty(name = "banco.projections.enabled", havingValue = "true", matchIfMissing = true)
    ProjectionRunner projectionRunner(
            JdbcTemplate jdbc, TransactionTemplate tx, JdbcEventStore eventStore,
            ContaProjector projector, MeterRegistry meters,
            @Value("${banco.projections.batch-size:200}") int batchSize) {
        return new ProjectionRunner(jdbc, tx, eventStore, projector, batchSize, meters);
    }

    @Bean
    @ConditionalOnProperty(name = "banco.projections.enabled", havingValue = "true", matchIfMissing = true)
    ProjectionScheduler projectionScheduler(ProjectionRunner runner) {
        return new ProjectionScheduler(runner);
    }

    static class ProjectionScheduler {
        private final ProjectionRunner runner;

        ProjectionScheduler(ProjectionRunner runner) {
            this.runner = runner;
        }

        @Scheduled(fixedDelayString = "${banco.projections.poll-interval:1000}")
        public void projectBatch() {
            runner.runOnce();
        }
    }
}
