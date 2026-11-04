package br.com.banco.domain;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Estado consolidado de uma conta numa versão do stream. É otimização descartável: se
 * {@link #SCHEMA_VERSION} mudar, snapshots antigos são ignorados e recriados a partir dos eventos.
 */
public record ContaSnapshot(int schemaVersion, UUID id, BigDecimal saldo, BigDecimal limite,
                            Conta.Status status, int versao) {

    public static final int SCHEMA_VERSION = 1;

    public boolean compativel() {
        return schemaVersion == SCHEMA_VERSION;
    }
}

