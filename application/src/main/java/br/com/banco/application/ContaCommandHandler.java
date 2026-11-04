package br.com.banco.application;

import br.com.banco.application.Commands.*;
import br.com.banco.application.port.ConcurrencyException;
import br.com.banco.application.port.EventMetadata;
import br.com.banco.application.port.EventStore;
import br.com.banco.application.port.IdempotencyStore;
import br.com.banco.application.port.SnapshotStore;
import br.com.banco.application.port.UnitOfWork;
import br.com.banco.domain.Conta;
import br.com.banco.domain.ContaSnapshot;

import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Executa comandos sobre o agregado Conta: idempotência, reidratação (snapshot + eventos posteriores),
 * decisão, append com concorrência otimista e retry em conflito (até 3 tentativas, com jitter curto).
 * Snapshots são gerados a cada N eventos, de forma assíncrona, após o commit do comando.
 */
public class ContaCommandHandler {

    static final int MAX_TENTATIVAS = 3;
    public static final int INTERVALO_SNAPSHOT_PADRAO = 100;

    private final EventStore store;
    private final IdempotencyStore idempotency;
    private final UnitOfWork uow;
    private final Clock clock;
    private final SnapshotStore snapshots;
    private final Executor snapshotExecutor;
    private final int intervaloSnapshot;

    public ContaCommandHandler(EventStore store, IdempotencyStore idempotency, UnitOfWork uow, Clock clock) {
        this(store, idempotency, uow, clock, SnapshotStore.NONE, Runnable::run, INTERVALO_SNAPSHOT_PADRAO);
    }

    public ContaCommandHandler(EventStore store, IdempotencyStore idempotency, UnitOfWork uow, Clock clock,
                               SnapshotStore snapshots, Executor snapshotExecutor, int intervaloSnapshot) {
        this.store = store;
        this.idempotency = idempotency;
        this.uow = uow;
        this.clock = clock;
        this.snapshots = snapshots;
        this.snapshotExecutor = snapshotExecutor;
        this.intervaloSnapshot = intervaloSnapshot;
    }

    public CommandResult handle(AbrirConta cmd) {
        return executar(cmd.contaId(), cmd.ctx(), true,
                c -> { throw new IllegalStateException("nunca chamado"); },
                () -> Conta.abrir(cmd.contaId(), cmd.titularRef(), cmd.limite(), clock));
    }

    public CommandResult handle(DepositarValor cmd) {
        return executar(cmd.contaId(), cmd.ctx(), c -> c.depositar(cmd.valor(), cmd.origem(), clock));
    }

    public CommandResult handle(SacarValor cmd) {
        return executar(cmd.contaId(), cmd.ctx(), c -> c.sacar(cmd.valor(), clock));
    }

    public CommandResult handle(BloquearConta cmd) {
        return executar(cmd.contaId(), cmd.ctx(), c -> c.bloquear(cmd.motivo(), clock));
    }

    public CommandResult handle(DesbloquearConta cmd) {
        return executar(cmd.contaId(), cmd.ctx(), c -> c.desbloquear(clock));
    }

    public CommandResult handle(EncerrarConta cmd) {
        return executar(cmd.contaId(), cmd.ctx(), c -> c.encerrar(clock));
    }

    public CommandResult handle(EstornarValor cmd) {
        return executar(cmd.contaId(), cmd.ctx(), c -> c.estornar(cmd.valor(), cmd.transferenciaId(), clock));
    }

    // ---------------------------------------------------------------------

    /** Resultado de uma tentativa; {@code snapshot} é não nulo quando um novo snapshot é devido. */
    private record Tentativa(CommandResult resultado, ContaSnapshot snapshot) {}

    private CommandResult executar(UUID contaId, Context ctx, Consumer<Conta> acao) {
        return executar(contaId, ctx, false, acao, null);
    }

    private CommandResult executar(UUID contaId, Context ctx, boolean abertura, Consumer<Conta> acao,
                                   Supplier<Conta> criar) {
        ConcurrencyException ultimo = null;
        for (int tentativa = 1; tentativa <= MAX_TENTATIVAS; tentativa++) {
            try {
                Tentativa t = uow.execute(() -> tentar(contaId, ctx, abertura, acao, criar));
                if (t.snapshot() != null) salvarSnapshotAsync("conta-" + contaId, t.snapshot());
                return t.resultado();
            } catch (ConcurrencyException e) {
                ultimo = e;
                if (tentativa < MAX_TENTATIVAS) {
                    LockSupport.parkNanos(ThreadLocalRandom.current().nextLong(1, 15) * 1_000_000L);
                }
            }
        }
        throw ultimo;
    }

    /** Uma tentativa completa dentro de uma transação; a decisão é refeita sobre o estado atual. */
    private Tentativa tentar(UUID contaId, Context ctx, boolean abertura, Consumer<Conta> acao,
                             Supplier<Conta> criar) {
        String key = ctx.idempotencyKey();
        if (key != null) {
            var anterior = idempotency.find(key);
            if (anterior.isPresent()) return new Tentativa(anterior.get(), null);
        }

        String streamId = "conta-" + contaId;
        Conta conta;
        int versaoPersistida;
        if (abertura) {
            var stream = store.load(streamId);
            if (stream.version() > 0) throw new ContaJaExisteException(contaId);
            conta = criar.get();
            versaoPersistida = stream.version();
        } else {
            var snap = snapshots.find(streamId);
            if (snap.isPresent()) {
                var stream = store.loadFrom(streamId, snap.get().versao());
                conta = Conta.restaurar(snap.get(), stream.events());
                versaoPersistida = stream.version();
            } else {
                var stream = store.load(streamId);
                conta = Conta.reconstruir(stream.events());
                versaoPersistida = stream.version();
            }
            acao.accept(conta);
        }

        var eventos = conta.eventosPendentes();
        store.append(streamId, versaoPersistida, eventos, EventMetadata.of(ctx.correlationId(), ctx.userId()));

        var resultado = new CommandResult(contaId, conta.versao());
        if (key != null) idempotency.save(key, resultado);
        boolean cruzouIntervalo = intervaloSnapshot > 0
                && conta.versao() / intervaloSnapshot > versaoPersistida / intervaloSnapshot;
        return new Tentativa(resultado, cruzouIntervalo ? conta.snapshot() : null);
    }

    private void salvarSnapshotAsync(String streamId, ContaSnapshot snapshot) {
        try {
            snapshotExecutor.execute(() -> {
                try {
                    snapshots.save(streamId, snapshot);
                } catch (RuntimeException ignorada) {
                    // snapshot é descartável: falhar aqui nunca afeta o comando já confirmado
                }
            });
        } catch (RuntimeException ignorada) {
            // executor rejeitou a tarefa; o próximo intervalo tentará de novo
        }
    }
}
