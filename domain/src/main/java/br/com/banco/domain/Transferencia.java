package br.com.banco.domain;

import br.com.banco.domain.TransferenciaEvent.*;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Estado da saga de transferência (event sourced). Cada passo valida a transição de estado e produz
 * um evento; {@link #aplicar} apenas aplica fatos, como no agregado Conta.
 */
public class Transferencia {

    public enum Status {
        NOVA, SOLICITADA, ORIGEM_DEBITADA, CONCLUIDA, REJEITADA, ESTORNANDO, ESTORNADA, FALHA_COMPENSACAO
    }

    private UUID id;
    private UUID origem;
    private UUID destino;
    private BigDecimal valor;
    private Status status = Status.NOVA;
    private int versao = 0;
    private final List<TransferenciaEvent> pendentes = new ArrayList<>();

    // ---------- Decisão ----------

    public static Transferencia solicitar(UUID id, UUID origem, UUID destino, BigDecimal valor, Clock clock) {
        if (origem.equals(destino)) throw new TransferenciaMesmaContaException(origem);
        if (valor == null || valor.signum() <= 0) throw new ValorInvalidoException(valor);
        Transferencia t = new Transferencia();
        t.registrar(new TransferenciaSolicitada(UUID.randomUUID(), id, origem, destino, valor, clock.instant()));
        return t;
    }

    public void registrarOrigemDebitada(Clock clock) {
        exigir(Status.SOLICITADA, "registrar débito da origem");
        registrar(new OrigemDebitada(UUID.randomUUID(), id, clock.instant()));
    }

    public void rejeitar(String motivo, Clock clock) {
        exigir(Status.SOLICITADA, "rejeitar");
        registrar(new TransferenciaRejeitada(UUID.randomUUID(), id, motivo, clock.instant()));
    }

    public void concluir(Clock clock) {
        exigir(Status.ORIGEM_DEBITADA, "concluir");
        registrar(new TransferenciaConcluida(UUID.randomUUID(), id, clock.instant()));
    }

    public void falharCreditoDestino(String motivo, Clock clock) {
        exigir(Status.ORIGEM_DEBITADA, "registrar falha no destino");
        registrar(new CreditoDestinoFalhou(UUID.randomUUID(), id, motivo, clock.instant()));
    }

    public void concluirEstorno(Clock clock) {
        exigir(Status.ESTORNANDO, "concluir estorno");
        registrar(new TransferenciaEstornada(UUID.randomUUID(), id, clock.instant()));
    }

    public void falharCompensacao(String motivo, Clock clock) {
        exigir(Status.ESTORNANDO, "registrar falha de compensação");
        registrar(new CompensacaoFalhou(UUID.randomUUID(), id, motivo, clock.instant()));
    }

    // ---------- Mutação ----------

    private void aplicar(TransferenciaEvent e) {
        switch (e) {
            case TransferenciaSolicitada ev -> {
                id = ev.transferId(); origem = ev.origem(); destino = ev.destino();
                valor = ev.valor(); status = Status.SOLICITADA;
            }
            case OrigemDebitada ev -> status = Status.ORIGEM_DEBITADA;
            case TransferenciaConcluida ev -> status = Status.CONCLUIDA;
            case TransferenciaRejeitada ev -> status = Status.REJEITADA;
            case CreditoDestinoFalhou ev -> status = Status.ESTORNANDO;
            case TransferenciaEstornada ev -> status = Status.ESTORNADA;
            case CompensacaoFalhou ev -> status = Status.FALHA_COMPENSACAO;
        }
        versao++;
    }

    private void registrar(TransferenciaEvent e) {
        aplicar(e);
        pendentes.add(e);
    }

    public static Transferencia reconstruir(List<TransferenciaEvent> historico) {
        Transferencia t = new Transferencia();
        historico.forEach(t::aplicar);
        return t;
    }

    private void exigir(Status esperado, String acao) {
        if (status != esperado) throw new TransferenciaEstadoInvalidaException(id, acao, status);
    }

    // ---------- Consulta ----------

    /** Estados finais: a saga não tem mais passos a executar. */
    public boolean terminal() {
        return status == Status.CONCLUIDA || status == Status.REJEITADA
                || status == Status.ESTORNADA || status == Status.FALHA_COMPENSACAO;
    }

    public List<TransferenciaEvent> eventosPendentes() { return List.copyOf(pendentes); }
    public int versaoPersistida() { return versao - pendentes.size(); }
    public UUID id() { return id; }
    public UUID origem() { return origem; }
    public UUID destino() { return destino; }
    public BigDecimal valor() { return valor; }
    public Status status() { return status; }
    public int versao() { return versao; }
}

