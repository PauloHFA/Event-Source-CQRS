package br.com.banco.infrastructure.projections;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
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
                            SELECT conta_id, saldo, limite, limite_diario, status, versao, atualizado_em
                            FROM rm_conta_saldo
                            WHERE conta_id = ?
                            """,
                    (rs, row) -> new ContaSaldoReadModel(
                            rs.getObject("conta_id", UUID.class),
                            rs.getBigDecimal("saldo"),
                            rs.getBigDecimal("limite"),
                            rs.getBigDecimal("limite_diario"),
                            rs.getString("status"),
                            rs.getInt("versao"),
                            rs.getTimestamp("atualizado_em").toInstant()),
                    contaId));
        } catch (EmptyResultDataAccessException notProjectedYet) {
            return Optional.empty();
        }
    }

    public ExtratoReadModel extrato(UUID contaId, int pagina, int tamanho) {
        return extrato(contaId, pagina, tamanho, null, null);
    }

    /**
     * RF-07: Consulta extrato com paginação e filtro opcional por período (de / ate).
     */
    public ExtratoReadModel extrato(UUID contaId, int pagina, int tamanho, Instant de, Instant ate) {
        StringBuilder where = new StringBuilder(" WHERE conta_id = ?");
        List<Object> countParams = new ArrayList<>();
        countParams.add(contaId);

        if (de != null) {
            where.append(" AND ocorrido_em >= ?");
            countParams.add(Timestamp.from(de));
        }
        if (ate != null) {
            where.append(" AND ocorrido_em <= ?");
            countParams.add(Timestamp.from(ate));
        }

        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rm_extrato" + where, Long.class, countParams.toArray());

        List<Object> queryParams = new ArrayList<>(countParams);
        queryParams.add(tamanho);
        queryParams.add(Math.multiplyExact((long) pagina, tamanho));

        List<MovimentacaoReadModel> movimentacoes = jdbc.query(
                "SELECT event_id, tipo, valor, saldo_apos, ocorrido_em FROM rm_extrato"
                        + where
                        + " ORDER BY ocorrido_em DESC, id DESC LIMIT ? OFFSET ?",
                (rs, row) -> new MovimentacaoReadModel(
                        rs.getObject("event_id", UUID.class),
                        rs.getString("tipo"),
                        rs.getBigDecimal("valor"),
                        rs.getBigDecimal("saldo_apos"),
                        rs.getTimestamp("ocorrido_em").toInstant()),
                queryParams.toArray());

        return new ExtratoReadModel(contaId, pagina, tamanho, total == null ? 0 : total, movimentacoes);
    }
}
