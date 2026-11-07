package br.com.banco.api.query;

import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;

@RestControllerAdvice
public class QueryApiExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(QueryApiExceptionHandler.class);

    @ExceptionHandler(ResponseStatusException.class)
    ProblemDetail responseStatus(ResponseStatusException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(e.getStatusCode(), e.getReason());
        problem.setType(URI.create("urn:banco:error:" + e.getStatusCode().value()));
        return problem;
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ProblemDetail parametroInvalido(ConstraintViolationException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Parâmetro de consulta inválido.");
        problem.setType(URI.create("urn:banco:error:PARAMETRO_INVALIDO"));
        return problem;
    }

    @ExceptionHandler(DataAccessException.class)
    ProblemDetail falhaDePersistencia(DataAccessException e) {
        LOGGER.error("Falha ao acessar os modelos de leitura.", e);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR,
                "Não foi possível concluir a leitura dos dados bancários.");
        problem.setType(URI.create("urn:banco:error:ERRO_PERSISTENCIA"));
        return problem;
    }
}
