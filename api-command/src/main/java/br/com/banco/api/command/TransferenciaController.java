package br.com.banco.api.command;

import br.com.banco.application.Commands.Context;
import br.com.banco.application.Commands.SolicitarTransferencia;
import br.com.banco.application.TransferenciaResult;
import br.com.banco.application.TransferenciaSaga;
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

    @GetMapping("/{id}")
    public TransferenciaResult consultar(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        ownership.requireTransferOwner(id, jwt.getSubject());
        return saga.consultar(id);
    }
}
