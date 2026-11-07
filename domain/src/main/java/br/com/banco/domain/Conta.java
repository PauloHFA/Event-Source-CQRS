package br.com.banco.domain;

import br.com.banco.domain.ContaEvent.*;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Agregado Conta. Separa decisão (valida e produz eventos) de mutação
 * ({@link #aplicar}, determinística, sem validação).
 */
public class Conta {

    public enum Status { NOVA, ATIVA, BLOQUEADA, ENCERRADA }

    private UUID id;
    private BigDecimal saldo = BigDecimal.ZERO;
    private BigDecimal limite = BigDecimal.ZERO;
    private BigDecimal limiteDiario; // null = sem limite diário configurado
    private BigDecimal totalDebitadoNoDia = BigDecimal.ZERO;
    private LocalDate dataDebitos;
    private Status status = Status.NOVA;
    private int versao = 0;
    private final List<ContaEvent> pendentes = new ArrayList<>();

    // ---------- Decisão (comandos) ----------

    public static Conta abrir(UUID id, String titularRef, BigDecimal limite, Clock clock) {
        if (limite == null || limite.signum() < 0) throw new ValorInvalidoException(limite);
        Conta c = new Conta();
        c.registrar(new ContaAberta(UUID.randomUUID(), id, titularRef, limite, clock.instant()));
        return c;
    }

    public void configurarLimiteDiario(BigDecimal novoLimite, Clock clock) {
        garantirOperavel();
        if (novoLimite != null && novoLimite.signum() < 0) {
            throw new ValorInvalidoException(novoLimite);
        }
        registrar(new LimiteDiarioConfigurado(UUID.randomUUID(), id, novoLimite, clock.instant()));
    }

    public void depositar(BigDecimal valor, String origem, Clock clock) {
        garantirOperavel();
        exigirPositivo(valor);
        registrar(new ValorDepositado(UUID.randomUUID(), id, valor, origem, clock.instant()));
    }

    public void sacar(BigDecimal valor, Clock clock) {
        garantirOperavel();
        if (status == Status.BLOQUEADA) throw new ContaBloqueadaException(id);
        exigirPositivo(valor);
        if (saldo.add(limite).compareTo(valor) < 0) throw new SaldoInsuficienteException(id);
        if (limiteDiario != null) {
            LocalDate hoje = clock.instant().atZone(ZoneOffset.UTC).toLocalDate();
            BigDecimal debitadoHoje = hoje.equals(dataDebitos) ? totalDebitadoNoDia : BigDecimal.ZERO;
            if (debitadoHoje.add(valor).compareTo(limiteDiario) > 0) {
                throw new LimiteDiarioExcedidoException(id, valor, limiteDiario, debitadoHoje);
            }
        }
        registrar(new ValorSacado(UUID.randomUUID(), id, valor, clock.instant()));
    }

    /** Compensação de uma transferência: crédito aceito mesmo com a conta bloqueada (RN-04). */
    public void estornar(BigDecimal valor, UUID transferenciaId, Clock clock) {
        garantirOperavel();
        exigirPositivo(valor);
        registrar(new ValorEstornado(UUID.randomUUID(), id, valor, transferenciaId, clock.instant()));
    }

    public void bloquear(String motivo, Clock clock) {
        garantirOperavel();
        if (status == Status.BLOQUEADA) return;
        registrar(new ContaBloqueada(UUID.randomUUID(), id, motivo, clock.instant()));
    }

    public void desbloquear(Clock clock) {
        garantirOperavel();
        if (status != Status.BLOQUEADA) return;
        registrar(new ContaDesbloqueada(UUID.randomUUID(), id, clock.instant()));
    }

    public void encerrar(Clock clock) {
        garantirOperavel();
        if (saldo.signum() != 0) throw new SaldoNaoZeradoException(id);
        registrar(new ContaEncerrada(UUID.randomUUID(), id, clock.instant()));
    }

    // ---------- Mutação (apenas aplica fatos, sem validação) ----------

    private void aplicar(ContaEvent e) {
        switch (e) {
            case ContaAberta ev -> { id = ev.contaId(); limite = ev.limite(); status = Status.ATIVA; }
            case ValorDepositado ev -> saldo = saldo.add(ev.valor());
            case ValorSacado ev -> {
                saldo = saldo.subtract(ev.valor());
                LocalDate data = ev.em().atZone(ZoneOffset.UTC).toLocalDate();
                if (data.equals(dataDebitos)) {
                    totalDebitadoNoDia = totalDebitadoNoDia.add(ev.valor());
                } else {
                    dataDebitos = data;
                    totalDebitadoNoDia = ev.valor();
                }
            }
            case ValorEstornado ev -> {
                saldo = saldo.add(ev.valor());
                LocalDate data = ev.em().atZone(ZoneOffset.UTC).toLocalDate();
                if (data.equals(dataDebitos)) {
                    totalDebitadoNoDia = totalDebitadoNoDia.subtract(ev.valor());
                    if (totalDebitadoNoDia.signum() < 0) totalDebitadoNoDia = BigDecimal.ZERO;
                }
            }
            case LimiteDiarioConfigurado ev -> limiteDiario = ev.novoLimite();
            case ContaBloqueada ev -> status = Status.BLOQUEADA;
            case ContaDesbloqueada ev -> status = Status.ATIVA;
            case ContaEncerrada ev -> status = Status.ENCERRADA;
        }
        versao++;
    }

    private void registrar(ContaEvent e) {
        aplicar(e);
        pendentes.add(e);
    }

    public static Conta reconstruir(List<ContaEvent> historico) {
        Conta c = new Conta();
        historico.forEach(c::aplicar); // não adiciona em 'pendentes'
        return c;
    }

    /** Reidrata a partir de um snapshot e dos eventos posteriores a ele. */
    public static Conta restaurar(ContaSnapshot s, List<ContaEvent> posteriores) {
        Conta c = new Conta();
        c.id = s.id();
        c.saldo = s.saldo();
        c.limite = s.limite();
        c.status = s.status();
        c.versao = s.versao();
        c.limiteDiario = s.limiteDiario();
        c.totalDebitadoNoDia = s.totalDebitadoNoDia() != null ? s.totalDebitadoNoDia() : BigDecimal.ZERO;
        c.dataDebitos = s.dataDebitos() != null ? LocalDate.parse(s.dataDebitos()) : null;
        posteriores.forEach(c::aplicar);
        return c;
    }

    public ContaSnapshot snapshot() {
        return new ContaSnapshot(ContaSnapshot.SCHEMA_VERSION, id, saldo, limite, status, versao,
                limiteDiario, totalDebitadoNoDia, dataDebitos != null ? dataDebitos.toString() : null);
    }

    private void garantirOperavel() {
        if (status == Status.NOVA || status == Status.ENCERRADA) throw new ContaInvalidaException(id);
    }

    private void exigirPositivo(BigDecimal v) {
        if (v == null || v.signum() <= 0) throw new ValorInvalidoException(v);
    }

    // ---------- Consulta ----------

    public List<ContaEvent> eventosPendentes() { return List.copyOf(pendentes); }

    public int versaoPersistida() { return versao - pendentes.size(); }

    public UUID id() { return id; }
    public BigDecimal saldo() { return saldo; }
    public BigDecimal limite() { return limite; }
    public BigDecimal limiteDiario() { return limiteDiario; }
    public BigDecimal totalDebitadoNoDia() { return totalDebitadoNoDia; }
    public LocalDate dataDebitos() { return dataDebitos; }
    public Status status() { return status; }
    public int versao() { return versao; }
}
