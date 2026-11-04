package br.com.banco.application.port;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Consulta de sagas não terminais, para detectar transferências travadas. */
public interface TransferenciaPendentes {

    /** Transferências não terminais cujo último evento ocorreu antes de {@code limite}. */
    List<UUID> pendentesAntesDe(Instant limite);
}

