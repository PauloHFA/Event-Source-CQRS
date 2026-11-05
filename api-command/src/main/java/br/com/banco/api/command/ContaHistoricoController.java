package br.com.banco.api.command;

import br.com.banco.application.ContaHistoricoService;
import br.com.banco.domain.ContaSnapshot;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.UUID;

/** Time travel (RF-08): reconstrói a conta a partir dos eventos (fonte da verdade), não do read model. */
@RestController
@RequestMapping("/contas/{id}/historico")
public class ContaHistoricoController {

    private final ContaHistoricoService historico;
    private final ContaOwnershipAuthorization ownership;

    public ContaHistoricoController(ContaHistoricoService historico, ContaOwnershipAuthorization ownership) {
        this.historico = historico;
        this.ownership = ownership;
    }

    /** {@code ?em=2026-01-15T10:00:00Z} ou {@code ?versao=12}. */
    @GetMapping("/estado")
    public ContaSnapshot estado(@PathVariable UUID id,
                                @RequestParam(required = false) Instant em,
                                @RequestParam(required = false) Integer versao,
                                @AuthenticationPrincipal Jwt jwt) {
        if ((em == null) == (versao == null)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Informe exatamente um de: em, versao");
        }
        ownership.requireOwner(id, jwt.getSubject());
        return em != null ? historico.estadoEm(id, em) : historico.estadoNaVersao(id, versao);
    }
}
