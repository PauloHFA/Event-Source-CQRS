package br.com.banco.domain;

/** Violação de regra de negócio: o comando é rejeitado e nenhum evento é gerado. */
public abstract class DomainException extends RuntimeException {
    protected DomainException(String message) {
        super(message);
    }
}

