package br.com.banco.infrastructure.projections;

import java.util.List;
import java.util.UUID;

public record ExtratoReadModel(UUID contaId, int pagina, int tamanho, long total,
                              List<MovimentacaoReadModel> movimentacoes) {
    public ExtratoReadModel {
        movimentacoes = List.copyOf(movimentacoes);
    }
}
