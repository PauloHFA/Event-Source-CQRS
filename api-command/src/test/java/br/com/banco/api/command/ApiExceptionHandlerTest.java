package br.com.banco.api.command;

import br.com.banco.application.ContaJaExisteException;
import br.com.banco.application.port.ConcurrencyException;
import br.com.banco.domain.LimiteDiarioExcedidoException;
import br.com.banco.domain.SaldoInsuficienteException;
import br.com.banco.domain.ValorInvalidoException;
import jakarta.validation.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

import java.math.BigDecimal;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ApiExceptionHandlerTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @Test
    void respostasDeErroIncluemCodigoEstavelEStatusCorrespondente() {
        assertProblem(handler.valorInvalido(new ValorInvalidoException(new BigDecimal("-1"))),
                HttpStatus.BAD_REQUEST, "VALOR_INVALIDO");
        assertProblem(handler.jaExiste(new ContaJaExisteException(UUID.randomUUID())),
                HttpStatus.CONFLICT, "CONTA_JA_EXISTE");
        assertProblem(handler.regraDeNegocio(new SaldoInsuficienteException(UUID.randomUUID())),
                HttpStatus.UNPROCESSABLE_ENTITY, "SALDO_INSUFICIENTE");
        assertProblem(handler.regraDeNegocio(new LimiteDiarioExcedidoException(UUID.randomUUID(), BigDecimal.TEN, BigDecimal.ONE, BigDecimal.ZERO)),
                HttpStatus.UNPROCESSABLE_ENTITY, "LIMITE_DIARIO_EXCEDIDO");
        assertProblem(handler.conflito(new ConcurrencyException("conta-1", 2)),
                HttpStatus.CONFLICT, "CONFLITO_DE_CONCORRENCIA");
        assertProblem(handler.parametroInvalido(new ConstraintViolationException("invalid", Set.of())),
                HttpStatus.BAD_REQUEST, "PARAMETRO_INVALIDO");
        assertProblem(handler.falhaDePersistencia(new DataAccessResourceFailureException("database offline")),
                HttpStatus.INTERNAL_SERVER_ERROR, "ERRO_PERSISTENCIA");
    }

    private static void assertProblem(ProblemDetail problem, HttpStatus status, String code) {
        assertThat(problem.getStatus()).isEqualTo(status.value());
        assertThat(problem.getProperties()).containsEntry("code", code);
        assertThat(problem.getDetail()).isNotBlank();
    }
}
