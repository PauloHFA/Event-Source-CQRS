package br.com.banco.application.port;

/** Outro comando gravou na mesma versão do stream antes de nós. */
public class ConcurrencyException extends RuntimeException {
    public ConcurrencyException(String streamId, int expectedVersion) {
        super("Conflito de versão no stream " + streamId + " (versão esperada: " + expectedVersion + ")");
    }
}
