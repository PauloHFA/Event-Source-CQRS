package br.com.banco.api.query;

import br.com.banco.infrastructure.projections.ContaReadModelQueries;
import br.com.banco.infrastructure.projections.ContaSaldoReadModel;
import br.com.banco.infrastructure.projections.ExtratoReadModel;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.UUID;

@Tag(name = "Contas - Consultas (Read Model)", description = "Consultas otimizadas aos read models (rm_conta_saldo e rm_extrato)")
@RestController
@Validated
@RequestMapping("/contas/{id}")
public class ContaQueryController {

    private final ContaReadModelQueries queries;
    private final ContaOwnershipAuthorization ownership;

    public ContaQueryController(ContaReadModelQueries queries, ContaOwnershipAuthorization ownership) {
        this.queries = queries;
        this.ownership = ownership;
    }

    /** RF-07: Consulta detalhes da conta no modelo de leitura. */
    @Operation(summary = "Consultar detalhes da conta", description = "Retorna saldo, limite, limite diário, status e versão projetados.")
    @GetMapping
    public ContaSaldoReadModel detalhes(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        ownership.requireOwner(id, jwt.getSubject());
        return queries.saldo(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                        "Conta ainda não projetada; tente novamente."));
    }

    /** RF-07: Consulta saldo atual no modelo de leitura. */
    @Operation(summary = "Consultar saldo atual", description = "Retorna o saldo projetado da conta.")
    @GetMapping("/saldo")
    public ContaSaldoReadModel saldo(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        ownership.requireOwner(id, jwt.getSubject());
        return queries.saldo(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                        "Saldo ainda não projetado; tente novamente."));
    }

    /** RF-07: Extrato paginado com filtro opcional por período (de / ate). */
    @Operation(summary = "Consultar extrato de movimentações", description = "Retorna extrato paginado com suporte opcional a filtro de período (de e ate em formato ISO-8601).")
    @GetMapping("/extrato")
    public ExtratoReadModel extrato(@PathVariable UUID id,
                                    @RequestParam(defaultValue = "0") @Min(0) int pagina,
                                    @RequestParam(defaultValue = "20") @Min(1) @Max(100) int tamanho,
                                    @RequestParam(required = false) Instant de,
                                    @RequestParam(required = false) Instant ate,
                                    @AuthenticationPrincipal Jwt jwt) {
        ownership.requireOwner(id, jwt.getSubject());
        if (queries.saldo(id).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Extrato ainda não projetado; tente novamente.");
        }
        return queries.extrato(id, pagina, tamanho, de, ate);
    }
}
