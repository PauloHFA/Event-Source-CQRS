package br.com.banco.api.command;

import br.com.banco.infrastructure.projections.ContaReadModelQueries;
import br.com.banco.infrastructure.projections.ContaSaldoReadModel;
import br.com.banco.infrastructure.projections.ExtratoReadModel;
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

import java.util.UUID;

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

    @GetMapping("/saldo")
    public ContaSaldoReadModel saldo(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        ownership.requireOwner(id, jwt.getSubject());
        return queries.saldo(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                        "Saldo ainda não projetado; tente novamente."));
    }

    @GetMapping("/extrato")
    public ExtratoReadModel extrato(@PathVariable UUID id,
                                    @RequestParam(defaultValue = "0") @Min(0) int pagina,
                                    @RequestParam(defaultValue = "20") @Min(1) @Max(100) int tamanho,
                                    @AuthenticationPrincipal Jwt jwt) {
        ownership.requireOwner(id, jwt.getSubject());
        if (queries.saldo(id).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Extrato ainda não projetado; tente novamente.");
        }
        return queries.extrato(id, pagina, tamanho);
    }
}
