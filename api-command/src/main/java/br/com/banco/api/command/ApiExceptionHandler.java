package br.com.banco.api.command;

import br.com.banco.application.ContaJaExisteException;
import br.com.banco.application.port.ConcurrencyException;
import br.com.banco.domain.ContaBloqueadaException;
import br.com.banco.domain.ContaInvalidaException;
import br.com.banco.domain.DomainException;
import br.com.banco.domain.LimiteDiarioExcedidoException;
import br.com.banco.domain.SaldoInsuficienteException;
import br.com.banco.domain.SaldoNaoZeradoException;
import br.com.banco.domain.TransferenciaEstadoInvalidaException;
import br.com.banco.domain.TransferenciaMesmaContaException;
import br.com.banco.domain.ValorInvalidoException;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ValorInvalidoException.class)
    ProblemDetail valorInvalido(ValorInvalidoException e) {
        return problem(HttpStatus.BAD_REQUEST, "VALOR_INVALIDO", e.getMessage());
    }

    @ExceptionHandler(ContaJaExisteException.class)
    ProblemDetail jaExiste(ContaJaExisteException e) {
        return problem(HttpStatus.CONFLICT, "CONTA_JA_EXISTE", e.getMessage());
    }

    /** Regras de negócio violadas: comando rejeitado, nenhum evento gravado. */
    @ExceptionHandler(DomainException.class)
    ProblemDetail regraDeNegocio(DomainException e) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, codigoRegra(e), e.getMessage());
    }

    /** Conflito persistente após as tentativas de retry. */
    @ExceptionHandler(ConcurrencyException.class)
    ProblemDetail conflito(ConcurrencyException e) {
        return problem(HttpStatus.CONFLICT, "CONFLITO_DE_CONCORRENCIA", e.getMessage());
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ProblemDetail parametroInvalido(ConstraintViolationException e) {
        return problem(HttpStatus.BAD_REQUEST, "PARAMETRO_INVALIDO", "Parâmetro de consulta inválido.");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail corpoInvalido(MethodArgumentNotValidException e) {
        return problem(HttpStatus.BAD_REQUEST, "REQUEST_INVALIDO", "Corpo da requisição inválido.");
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ProblemDetail jsonInvalido(HttpMessageNotReadableException e) {
        return problem(HttpStatus.BAD_REQUEST, "REQUEST_INVALIDO", "Corpo da requisição inválido.");
    }

    @ExceptionHandler(DataAccessException.class)
    ProblemDetail falhaDePersistencia(DataAccessException e) {
        LOGGER.error("Falha ao acessar a persistência da aplicação.", e);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "ERRO_PERSISTENCIA",
                "Não foi possível concluir a operação.");
    }

    private static ProblemDetail problem(HttpStatus status, String code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setProperty("code", code);
        return problem;
    }

    private static String codigoRegra(DomainException e) {
        if (e instanceof SaldoInsuficienteException) return "SALDO_INSUFICIENTE";
        if (e instanceof LimiteDiarioExcedidoException) return "LIMITE_DIARIO_EXCEDIDO";
        if (e instanceof ContaBloqueadaException) return "CONTA_BLOQUEADA";
        if (e instanceof ContaInvalidaException) return "CONTA_INVALIDA";
        if (e instanceof SaldoNaoZeradoException) return "SALDO_NAO_ZERADO";
        if (e instanceof TransferenciaEstadoInvalidaException) return "TRANSFERENCIA_ESTADO_INVALIDO";
        if (e instanceof TransferenciaMesmaContaException) return "TRANSFERENCIA_MESMA_CONTA";
        return "REGRA_DE_NEGOCIO_VIOLADA";
    }
}
