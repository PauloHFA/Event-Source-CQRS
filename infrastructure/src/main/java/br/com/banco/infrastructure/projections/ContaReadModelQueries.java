package br.com.banco.infrastructure.projections;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class ContaReadModelQueries {

    private final JdbcTemplate jdbc;

    public ContaReadModelQueries(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<ContaSaldoReadModel> saldo(UUID contaId) {
        try {
            return Optional.ofNullable(jdbc.queryForObject("""
                            SELECT conta_id, saldo, limite, status, versao, atualizado_em
                            FROM rm_conta_saldo
                            WHERE conta_id = ?
                            """,
                    (rs, row) -> new ContaSaldoReadModel(
                            rs.getObject("conta_id", UUID.class),
                            rs.getBigDecimal("saldo"),
                            rs.getBigDecimal("limite"),
                            rs.getString("status"),
                            rs.getInt("versao"),
                            rs.getTimestamp("atualizado_em").toInstant()),
                    contaId));
        } catch (EmptyResultDataAccessException notProjectedYet) {
            return Optional.empty();
        }
    }

    public ExtratoReadModel extrato(UUID contaId, int pagina, int tamanho) {
        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rm_extrato WHERE conta_id = ?", Long.class, contaId);
        List<MovimentacaoReadModel> movimentacoes = jdbc.query("""
                        SELECT event_id, tipo, valor, saldo_apos, ocorrido_em
                        FROM rm_extrato
                        WHERE conta_id = ?
                        ORDER BY ocorrido_em DESC, id DESC
                        LIMIT ? OFFSET ?
                        """,
                (rs, row) -> new MovimentacaoReadModel(
                        rs.getObject("event_id", UUID.class),
                        rs.getString("tipo"),
                        rs.getBigDecimal("valor"),
                        rs.getBigDecimal("saldo_apos"),
                        rs.getTimestamp("ocorrido_em").toInstant()),
                contaId, tamanho, Math.multiplyExact((long) pagina, tamanho));
        return new ExtratoReadModel(contaId, pagina, tamanho, total == null ? 0 : total, movimentacoes);
    }
}
