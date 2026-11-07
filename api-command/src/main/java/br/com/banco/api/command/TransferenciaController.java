package br.com.banco.api.command;

import br.com.banco.application.Commands.Context;
import br.com.banco.application.Commands.SolicitarTransferencia;
import br.com.banco.application.TransferenciaResult;
import br.com.banco.application.TransferenciaSaga;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Transferência entre contas via saga. Com Idempotency-Key, o transferId é derivado da chave. */
@Tag(name = "Transferências (Saga)", description = "Coordenação de transferências entre contas com débito, crédito e compensação")
@RestController
@RequestMapping("/transferencias")
public class TransferenciaController {

    record TransferenciaRequest(@NotNull UUID origem, @NotNull UUID destino, @NotNull @Positive BigDecimal valor) {}

    private final TransferenciaSaga saga;
    private final ContaOwnershipAuthorization ownership;

    public TransferenciaController(TransferenciaSaga saga, ContaOwnershipAuthorization ownership) {
        this.saga = saga;
        this.ownership = ownership;
    }

    @Operation(summary = "Solicitar transferência", description = "Inicia a saga de transferência debitando a origem e creditando o destino.")
    @PostMapping
    public ResponseEntity<TransferenciaResult> solicitar(
            @Valid @RequestBody TransferenciaRequest req,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @AuthenticationPrincipal Jwt jwt) {
        String subject = jwt.getSubject();
        ownership.requireOwner(req.origem(), subject);
        UUID transferId = key != null
                ? UUID.nameUUIDFromBytes((subject + "\0transferencia\0" + key).getBytes(StandardCharsets.UTF_8))
                : UUID.randomUUID();
        var r = saga.solicitar(new SolicitarTransferencia(transferId, req.origem(), req.destino(), req.valor(),
                new Context(null, transferId, subject)));
        return ResponseEntity.created(URI.create("/transferencias/" + transferId)).body(r);
    }

    @Operation(summary = "Consultar status da transferência", description = "Retorna o estado atual da saga (INICIADA, DEBITADA, CONCLUIDA ou FALHA).")
    @GetMapping("/{id}")
    public TransferenciaResult consultar(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        ownership.requireTransferOwner(id, jwt.getSubject());
        return saga.consultar(id);
    }
}
