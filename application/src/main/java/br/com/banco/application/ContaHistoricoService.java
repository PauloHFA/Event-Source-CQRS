package br.com.banco.application;

import br.com.banco.application.port.EventStore;
import br.com.banco.domain.Conta;
import br.com.banco.domain.ContaEvent;
import br.com.banco.domain.ContaSnapshot;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Time travel (RF-08): reconstrói o estado da conta numa data ou versão passada a partir dos eventos. */
public class ContaHistoricoService {

    private final EventStore store;

    public ContaHistoricoService(EventStore store) {
        this.store = store;
    }

    /** Estado considerando apenas eventos com {@code occurredAt <= data}. */
    public ContaSnapshot estadoEm(UUID contaId, Instant data) {
        List<ContaEvent> eventos = store.load("conta-" + contaId).events().stream()
                .filter(e -> !e.em().isAfter(data))
                .toList();
        return Conta.reconstruir(eventos).snapshot();
    }

    /** Estado após aplicar os {@code versao} primeiros eventos do stream. */
    public ContaSnapshot estadoNaVersao(UUID contaId, int versao) {
        List<ContaEvent> eventos = store.load("conta-" + contaId).events();
        return Conta.reconstruir(eventos.subList(0, Math.max(0, Math.min(versao, eventos.size())))).snapshot();
    }
}

