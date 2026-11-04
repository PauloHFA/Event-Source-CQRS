package br.com.banco.api.command;

import br.com.banco.application.ContaJaExisteException;
import br.com.banco.application.port.ConcurrencyException;
import br.com.banco.domain.DomainException;
import br.com.banco.domain.ValorInvalidoException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(ValorInvalidoException.class)
    ProblemDetail valorInvalido(ValorInvalidoException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(ContaJaExisteException.class)
    ProblemDetail jaExiste(ContaJaExisteException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    /** Regras de negócio violadas: comando rejeitado, nenhum evento gravado. */
    @ExceptionHandler(DomainException.class)
    ProblemDetail regraDeNegocio(DomainException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
    }

    /** Conflito persistente após as tentativas de retry. */
    @ExceptionHandler(ConcurrencyException.class)
    ProblemDetail conflito(ConcurrencyException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }
}

