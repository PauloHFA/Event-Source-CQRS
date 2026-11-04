package br.com.banco.application;

import br.com.banco.application.Commands.*;
import br.com.banco.application.port.ConcurrencyException;
import br.com.banco.application.port.EventMetadata;
import br.com.banco.application.port.EventStore;
import br.com.banco.application.port.TransferenciaPendentes;
import br.com.banco.domain.DomainException;
import br.com.banco.domain.Transferencia;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * Saga (process manager) de transferência. Um comando afeta um único agregado: a saga coordena
 * saque na origem, depósito no destino e, em caso de falha no destino, o estorno compensatório.
 * <p>
 * Cada passo em Conta usa uma Idempotency-Key derivada do transferId, então reexecutar a saga
 * (após falha, conflito ou recuperação de saga travada) nunca duplica efeitos. O estado da saga
 * também é event sourced, com o transferId como correlationId.
 */
public class TransferenciaSaga {

    static final int MAX_PASSOS = 10;
    public static final String USUARIO_SISTEMA = "saga";

    private final EventStore store;
    private final ContaCommandHandler contas;
    private final TransferenciaPendentes pendentes;
    private final Clock clock;

    public TransferenciaSaga(EventStore store, ContaCommandHandler contas, TransferenciaPendentes pendentes,
                             Clock clock) {
        this.store = store;
        this.contas = contas;
        this.pendentes = pendentes;
        this.clock = clock;
    }

    /** Registra a solicitação (idempotente por transferId) e executa a saga até onde for possível. */
    public TransferenciaResult solicitar(SolicitarTransferencia cmd) {
        String streamId = streamId(cmd.transferId());
        if (store.loadTransferencia(streamId).version() == 0) {
            var t = Transferencia.solicitar(cmd.transferId(), cmd.origem(), cmd.destino(), cmd.valor(), clock);
            try {
                store.append(streamId, 0, t.eventosPendentes(), meta(cmd.transferId(), cmd.ctx().userId()));
            } catch (ConcurrencyException jaCriada) {
                // outra requisição com o mesmo transferId criou o stream primeiro
            }
        }
        return avancar(cmd.transferId(), cmd.ctx().userId());
    }

    public TransferenciaResult consultar(UUID transferId) {
        var t = Transferencia.reconstruir(store.loadTransferencia(streamId(transferId)).events());
        return new TransferenciaResult(transferId, t.status());
    }

    /** Executa os passos pendentes até um estado terminal (ou até esgotar as tentativas). */
    public TransferenciaResult avancar(UUID transferId, String userId) {
        String streamId = streamId(transferId);
        for (int passo = 0; passo < MAX_PASSOS; passo++) {
            var stream = store.loadTransferencia(streamId);
            var t = Transferencia.reconstruir(stream.events());
            if (t.terminal()) return new TransferenciaResult(transferId, t.status());
            try {
                executarPasso(t, userId);
                store.append(streamId, stream.version(), t.eventosPendentes(), meta(transferId, userId));
            } catch (ConcurrencyException conflito) {
                // outro processo avançou a saga ou a conta está disputada: recarrega e tenta de novo
            }
        }
        return consultar(transferId);
    }

    /**
     * Retoma sagas não terminais paradas há mais que {@code timeout} (prazo por etapa).
     * Retorna quantas foram retomadas.
     */
    public int recuperarTravadas(Duration timeout) {
        var ids = pendentes.pendentesAntesDe(clock.instant().minus(timeout));
        ids.forEach(id -> avancar(id, USUARIO_SISTEMA));
        return ids.size();
    }

    // ---------------------------------------------------------------------

    private void executarPasso(Transferencia t, String userId) {
        UUID id = t.id();
        switch (t.status()) {
            case SOLICITADA -> {
                var falha = tentar(() -> contas.handle(new SacarValor(t.origem(), t.valor(),
                        ctx(id, "saque", userId))));
                if (falha.isPresent()) t.rejeitar(falha.get(), clock);
                else t.registrarOrigemDebitada(clock);
            }
            case ORIGEM_DEBITADA -> {
                var falha = tentar(() -> contas.handle(new DepositarValor(t.destino(), t.valor(),
                        "transferencia:" + id, ctx(id, "deposito", userId))));
                if (falha.isPresent()) t.falharCreditoDestino(falha.get(), clock);
                else t.concluir(clock);
            }
            case ESTORNANDO -> {
                var falha = tentar(() -> contas.handle(new EstornarValor(t.origem(), t.valor(), id,
                        ctx(id, "estorno", userId))));
                if (falha.isPresent()) t.falharCompensacao(falha.get(), clock);
                else t.concluirEstorno(clock);
            }
            default -> throw new IllegalStateException("Estado sem passo: " + t.status());
        }
    }

    /** Recusas de negócio viram resultado; falhas técnicas (ex.: ConcurrencyException) propagam. */
    private Optional<String> tentar(Runnable passo) {
        try {
            passo.run();
            return Optional.empty();
        } catch (DomainException recusa) {
            return Optional.of(recusa.getMessage());
        }
    }

    private static Context ctx(UUID transferId, String passo, String userId) {
        return new Context("transfer-" + transferId + "-" + passo, transferId, userId);
    }

    private static EventMetadata meta(UUID transferId, String userId) {
        return EventMetadata.of(transferId, userId);
    }

    static String streamId(UUID transferId) {
        return "transfer-" + transferId;
    }

    public static String streamIdOf(UUID transferId) {
        return streamId(transferId);
    }
}

