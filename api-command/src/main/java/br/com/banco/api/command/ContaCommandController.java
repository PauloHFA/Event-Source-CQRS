package br.com.banco.api.command;

import br.com.banco.application.CommandResult;
import br.com.banco.application.Commands.*;
import br.com.banco.application.ContaCommandHandler;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Positive;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "Contas - Comandos", description = "Operações de escrita sobre a conta corrente (Event Sourcing)")
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

    @Operation(summary = "Abrir nova conta corrente", description = "Produz o evento inicial ContaAberta com limite e titular do JWT.")
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

    @Operation(summary = "Configurar limite diário", description = "Define o valor máximo acumulado de débitos permitidos por dia civil (RN-07).")
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

    @Operation(summary = "Realizar depósito", description = "Credita valor na conta e produz o evento ValorDepositado.")
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

    @Operation(summary = "Realizar saque", description = "Debita valor da conta respeitando saldo, limite de cheque especial e limite diário.")
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

    @Operation(summary = "Bloquear conta", description = "Bloqueia a conta para movimentações de débito e crédito.")
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

    @Operation(summary = "Desbloquear conta", description = "Restaura o estado ativo da conta permitindo novas movimentações.")
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

    @Operation(summary = "Encerrar conta", description = "Encerra definitivamente a conta (requer saldo zerado).")
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
