package br.com.banco.domain;

import br.com.banco.domain.ContaEvent.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class ContaReconstrucaoBenchmarkTest {

    private static final int TOTAL_EVENTOS = 10_000;
    private static final int SNAPSHOT_INTERVAL = 100;

    @Test
    @DisplayName("RNF-06: Reconstrução de conta com 10.000 eventos usando snapshot deve levar menos de 50 ms")
    void rnf06_reconstrucaoComSnapshotDeveSerInferiorA50Milissegundos() {
        UUID contaId = UUID.randomUUID();
        Instant baseTime = Instant.parse("2026-01-01T00:00:00Z");

        // 1. Gerar 10.000 eventos em memória
        List<ContaEvent> historico = new ArrayList<>(TOTAL_EVENTOS);
        historico.add(new ContaAberta(UUID.randomUUID(), contaId, "titular-benchmark", new BigDecimal("1000.00"), baseTime));

        for (int i = 1; i < TOTAL_EVENTOS; i++) {
            Instant eventTime = baseTime.plusSeconds(i);
            if (i % 5 == 0) {
                historico.add(new ValorSacado(UUID.randomUUID(), contaId, new BigDecimal("10.00"), eventTime));
            } else {
                historico.add(new ValorDepositado(UUID.randomUUID(), contaId, new BigDecimal("20.00"), "CAIXA", eventTime));
            }
        }

        // 2. Snapshot simulado tirado no evento mais recente múltiplo de 100 (9.900)
        int snapshotPoint = (TOTAL_EVENTOS / SNAPSHOT_INTERVAL) * SNAPSHOT_INTERVAL - SNAPSHOT_INTERVAL; // 9900
        ContaSnapshot snapshot = Conta.reconstruir(historico.subList(0, snapshotPoint)).snapshot();
        List<ContaEvent> eventosPosteriores = historico.subList(snapshotPoint, TOTAL_EVENTOS); // 100 eventos

        // 3. Warm-up da JVM (compilação JIT)
        for (int w = 0; w < 50; w++) {
            Conta.restaurar(snapshot, eventosPosteriores);
        }

        // 4. Medição de 100 execuções de reidratação com snapshot
        int iteracoes = 100;
        long tempoTotalNanos = 0;
        long tempoMaxNanos = 0;

        for (int i = 0; i < iteracoes; i++) {
            long inicio = System.nanoTime();
            Conta contaReidratada = Conta.restaurar(snapshot, eventosPosteriores);
            long duracao = System.nanoTime() - inicio;

            tempoTotalNanos += duracao;
            if (duracao > tempoMaxNanos) {
                tempoMaxNanos = duracao;
            }

            assertThat(contaReidratada.versao()).isEqualTo(TOTAL_EVENTOS);
        }

        double mediaMs = (tempoTotalNanos / (double) iteracoes) / 1_000_000.0;
        double maxMs = tempoMaxNanos / 1_000_000.0;

        System.out.printf("[BENCHMARK RNF-06] Reconstrução via Snapshot (10.000 eventos): Média = %.3f ms | Máx = %.3f ms (Meta: < 50 ms)%n",
                mediaMs, maxMs);

        // Asserção estrita da meta RNF-06: < 50 ms
        assertThat(maxMs)
                .as("O tempo máximo de reconstrução com snapshot para 10.000 eventos deve ser menor que 50 ms")
                .isLessThan(50.0);
        assertThat(mediaMs)
                .as("O tempo médio de reconstrução com snapshot deve ser amplamente inferior a 50 ms")
                .isLessThan(10.0);
    }

    @Test
    @DisplayName("Comparativo de desempenho: Replay completo vs Replay com snapshot em 10.000 eventos")
    void comparativoReplayCompletoVsSnapshot() {
        UUID contaId = UUID.randomUUID();
        Instant baseTime = Instant.parse("2026-01-01T00:00:00Z");

        List<ContaEvent> historico = new ArrayList<>(TOTAL_EVENTOS);
        historico.add(new ContaAberta(UUID.randomUUID(), contaId, "titular-benchmark", new BigDecimal("500.00"), baseTime));
        for (int i = 1; i < TOTAL_EVENTOS; i++) {
            historico.add(new ValorDepositado(UUID.randomUUID(), contaId, new BigDecimal("1.00"), "BENCH", baseTime.plusSeconds(i)));
        }

        ContaSnapshot snapshot = Conta.reconstruir(historico.subList(0, 9_900)).snapshot();
        List<ContaEvent> posteriores = historico.subList(9_900, TOTAL_EVENTOS);

        // Mede replay completo (sem snapshot)
        long t0 = System.nanoTime();
        Conta c1 = Conta.reconstruir(historico);
        long duracaoCompletoMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);

        // Mede replay com snapshot
        long t1 = System.nanoTime();
        Conta c2 = Conta.restaurar(snapshot, posteriores);
        long duracaoSnapshotMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t1);

        assertThat(c1).usingRecursiveComparison().isEqualTo(c2);
        System.out.printf("[BENCHMARK COMPARATIVO] Replay total de 10k eventos: %d ms | Replay via Snapshot: %d ms%n",
                duracaoCompletoMs, duracaoSnapshotMs);
    }
}
