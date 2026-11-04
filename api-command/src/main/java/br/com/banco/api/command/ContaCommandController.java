package br.com.banco.api.command;

import br.com.banco.application.CommandResult;
import br.com.banco.application.Commands.*;
import br.com.banco.application.ContaCommandHandler;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.net.URI;
import java.util.UUID;

/** API de comandos task-based; a validação de formato fica na borda, as regras de negócio no agregado. */
@RestController
@RequestMapping("/contas")
public class ContaCommandController {

    record AbrirContaRequest(@NotBlank String titularRef, @NotNull @PositiveOrZero BigDecimal limite) {}
    record DepositoRequest(@NotNull @Positive BigDecimal valor, @NotBlank String origem) {}
    record SaqueRequest(@NotNull @Positive BigDecimal valor) {}
    record BloqueioRequest(@NotBlank String motivo) {}

    private final ContaCommandHandler handler;

    public ContaCommandController(ContaCommandHandler handler) {
        this.handler = handler;
    }

    @PostMapping
    public ResponseEntity<CommandResult> abrir(@Valid @RequestBody AbrirContaRequest req,
                                               @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                               @RequestHeader(value = "X-Correlation-Id", required = false) UUID correlationId,
                                               @RequestHeader(value = "X-User-Id", defaultValue = "anonimo") String userId) {
        var contaId = UUID.randomUUID();
        var r = handler.handle(new AbrirConta(contaId, req.titularRef(), req.limite(), ctx(key, correlationId, userId)));
        return ResponseEntity.created(URI.create("/contas/" + r.contaId())).body(r);
    }

    @PostMapping("/{id}/depositos")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public CommandResult depositar(@PathVariable UUID id, @Valid @RequestBody DepositoRequest req,
                                   @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                   @RequestHeader(value = "X-Correlation-Id", required = false) UUID correlationId,
                                   @RequestHeader(value = "X-User-Id", defaultValue = "anonimo") String userId) {
        return handler.handle(new DepositarValor(id, req.valor(), req.origem(), ctx(key, correlationId, userId)));
    }

    @PostMapping("/{id}/saques")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public CommandResult sacar(@PathVariable UUID id, @Valid @RequestBody SaqueRequest req,
                               @RequestHeader(value = "Idempotency-Key", required = false) String key,
                               @RequestHeader(value = "X-Correlation-Id", required = false) UUID correlationId,
                               @RequestHeader(value = "X-User-Id", defaultValue = "anonimo") String userId) {
        return handler.handle(new SacarValor(id, req.valor(), ctx(key, correlationId, userId)));
    }

    @PostMapping("/{id}/bloqueio")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public CommandResult bloquear(@PathVariable UUID id, @Valid @RequestBody BloqueioRequest req,
                                  @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                  @RequestHeader(value = "X-Correlation-Id", required = false) UUID correlationId,
                                  @RequestHeader(value = "X-User-Id", defaultValue = "anonimo") String userId) {
        return handler.handle(new BloquearConta(id, req.motivo(), ctx(key, correlationId, userId)));
    }

    @DeleteMapping("/{id}/bloqueio")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public CommandResult desbloquear(@PathVariable UUID id,
                                     @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                     @RequestHeader(value = "X-Correlation-Id", required = false) UUID correlationId,
                                     @RequestHeader(value = "X-User-Id", defaultValue = "anonimo") String userId) {
        return handler.handle(new DesbloquearConta(id, ctx(key, correlationId, userId)));
    }

    @PostMapping("/{id}/encerramento")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public CommandResult encerrar(@PathVariable UUID id,
                                  @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                  @RequestHeader(value = "X-Correlation-Id", required = false) UUID correlationId,
                                  @RequestHeader(value = "X-User-Id", defaultValue = "anonimo") String userId) {
        return handler.handle(new EncerrarConta(id, ctx(key, correlationId, userId)));
    }

    private static Context ctx(String key, UUID correlationId, String userId) {
        return new Context(key, correlationId != null ? correlationId : UUID.randomUUID(), userId);
    }
}

