package br.com.banco.domain;

import br.com.banco.domain.ContaEvent.*;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ContaSnapshotTest {

    private static final Instant T = Instant.parse("2026-01-01T10:00:00Z");

    @Test
    void CA06_comOuSemSnapshotOEstadoEhIdentico() {
        UUID id = UUID.randomUUID();
        List<ContaEvent> historico = new ArrayList<>();
        historico.add(new ContaAberta(UUID.randomUUID(), id, "t", new BigDecimal("100"), T));
        for (int i = 1; i <= 20; i++) {
            historico.add(i % 3 == 0
                    ? new ValorSacado(UUID.randomUUID(), id, new BigDecimal("7.25"), T)
                    : new ValorDepositado(UUID.randomUUID(), id, new BigDecimal("10.10"), "X", T));
        }
        historico.add(new ContaBloqueada(UUID.randomUUID(), id, "m", T));
        Conta completa = Conta.reconstruir(historico);

        // para qualquer ponto de corte k, snapshot(k) + eventos restantes == replay completo
        for (int k = 1; k <= historico.size(); k++) {
            ContaSnapshot snap = Conta.reconstruir(historico.subList(0, k)).snapshot();
            Conta viaSnapshot = Conta.restaurar(snap, historico.subList(k, historico.size()));
            assertThat(viaSnapshot).usingRecursiveComparison().isEqualTo(completa);
        }
    }

    @Test
    void snapshotDeOutraVersaoDeEsquemaEhIncompativel() {
        var antigo = new ContaSnapshot(0, UUID.randomUUID(), BigDecimal.ZERO, BigDecimal.ZERO, Conta.Status.ATIVA, 1);
        assertThat(antigo.compativel()).isFalse();
    }
}

