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
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/** API de comandos task-based; a validação de formato fica na borda, as regras de negócio no agregado. */
@RestController
@RequestMapping("/contas")
public class ContaCommandController {

    record AbrirContaRequest(@NotNull @PositiveOrZero BigDecimal limite) {}
    record ConfigurarLimiteDiarioRequest(@NotNull @PositiveOrZero BigDecimal limiteDiario) {}
    record DepositoRequest(@NotNull @Positive BigDecimal valor, @NotBlank String origem) {}
    record SaqueRequest(@NotNull @Positive BigDecimal valor) {}
    record BloqueioRequest(@NotBlank String motivo) {}

    private final ContaCommandHandler handler;
    private final ContaOwnershipAuthorization ownership;

    public ContaCommandController(ContaCommandHandler handler, ContaOwnershipAuthorization ownership) {
        this.handler = handler;
        this.ownership = ownership;
    }

    @PostMapping
    public ResponseEntity<CommandResult> abrir(@Valid @RequestBody AbrirContaRequest req,
                                               @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                               @RequestHeader(value = "X-Correlation-Id", required = false) UUID correlationId,
                                               @AuthenticationPrincipal Jwt jwt) {
        var contaId = UUID.randomUUID();
        var r = handler.handle(new AbrirConta(contaId, jwt.getSubject(), req.limite(),
                ctx(key, correlationId, jwt.getSubject(), "abrir-conta")));
        return ResponseEntity.created(URI.create("/contas/" + r.contaId())).body(r);
    }

    @PutMapping("/{id}/limite-diario")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public CommandResult configurarLimiteDiario(@PathVariable UUID id,
                                                @Valid @RequestBody ConfigurarLimiteDiarioRequest req,
                                                @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                                @RequestHeader(value = "X-Correlation-Id", required = false) UUID correlationId,
                                                @AuthenticationPrincipal Jwt jwt) {
        ownership.requireOwner(id, jwt.getSubject());
        return handler.handle(new ConfigurarLimiteDiario(id, req.limiteDiario(),
                ctx(key, correlationId, jwt.getSubject(), "limite-diario:" + id)));
    }

    @PostMapping("/{id}/depositos")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public CommandResult depositar(@PathVariable UUID id, @Valid @RequestBody DepositoRequest req,
                                   @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                   @RequestHeader(value = "X-Correlation-Id", required = false) UUID correlationId,
                                   @AuthenticationPrincipal Jwt jwt) {
        ownership.requireOwner(id, jwt.getSubject());
        return handler.handle(new DepositarValor(id, req.valor(), req.origem(),
                ctx(key, correlationId, jwt.getSubject(), "deposito:" + id)));
    }

    @PostMapping("/{id}/saques")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public CommandResult sacar(@PathVariable UUID id, @Valid @RequestBody SaqueRequest req,
                               @RequestHeader(value = "Idempotency-Key", required = false) String key,
                               @RequestHeader(value = "X-Correlation-Id", required = false) UUID correlationId,
                               @AuthenticationPrincipal Jwt jwt) {
        ownership.requireOwner(id, jwt.getSubject());
        return handler.handle(new SacarValor(id, req.valor(),
                ctx(key, correlationId, jwt.getSubject(), "saque:" + id)));
    }

    @PostMapping("/{id}/bloqueio")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public CommandResult bloquear(@PathVariable UUID id, @Valid @RequestBody BloqueioRequest req,
                                  @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                  @RequestHeader(value = "X-Correlation-Id", required = false) UUID correlationId,
                                  @AuthenticationPrincipal Jwt jwt) {
        ownership.requireOwner(id, jwt.getSubject());
        return handler.handle(new BloquearConta(id, req.motivo(),
                ctx(key, correlationId, jwt.getSubject(), "bloqueio:" + id)));
    }

    @DeleteMapping("/{id}/bloqueio")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public CommandResult desbloquear(@PathVariable UUID id,
                                     @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                     @RequestHeader(value = "X-Correlation-Id", required = false) UUID correlationId,
                                     @AuthenticationPrincipal Jwt jwt) {
        ownership.requireOwner(id, jwt.getSubject());
        return handler.handle(new DesbloquearConta(id,
                ctx(key, correlationId, jwt.getSubject(), "desbloqueio:" + id)));
    }

    @PostMapping("/{id}/encerramento")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public CommandResult encerrar(@PathVariable UUID id,
                                  @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                  @RequestHeader(value = "X-Correlation-Id", required = false) UUID correlationId,
                                  @AuthenticationPrincipal Jwt jwt) {
        ownership.requireOwner(id, jwt.getSubject());
        return handler.handle(new EncerrarConta(id,
                ctx(key, correlationId, jwt.getSubject(), "encerramento:" + id)));
    }

    private static Context ctx(String key, UUID correlationId, String userId, String operation) {
        return new Context(scopedKey(key, userId, operation),
                correlationId != null ? correlationId : UUID.randomUUID(), userId);
    }

    private static String scopedKey(String key, String userId, String operation) {
        if (key == null) return null;
        try {
            byte[] input = (userId + "\0" + operation + "\0" + key).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponível para escopo da chave idempotente.", e);
        }
    }
}
