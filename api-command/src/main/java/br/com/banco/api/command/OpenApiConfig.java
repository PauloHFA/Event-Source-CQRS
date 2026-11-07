package br.com.banco.api.command;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    public static final String SECURITY_SCHEME_NAME = "BearerAuth";

    @Bean
    OpenAPI customOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Banco CQRS — Command API")
                        .version("1.0.0")
                        .description("API de escrita (Write Side) do núcleo bancário com Event Sourcing. "
                                + "Responsável por abertura de contas, depósitos, saques, limites diários, "
                                + "bloqueio/encerramento, transferências (Saga) e reconstrução histórica (Time Travel).")
                        .contact(new Contact().name("Paulo").url("https://github.com/PauloHFA/Event-Source-CQRS")))
                .addSecurityItem(new SecurityRequirement().addList(SECURITY_SCHEME_NAME))
                .components(new Components()
                        .addSecuritySchemes(SECURITY_SCHEME_NAME,
                                new SecurityScheme()
                                        .name(SECURITY_SCHEME_NAME)
                                        .type(SecurityScheme.Type.HTTP)
                                        .scheme("bearer")
                                        .bearerFormat("JWT")
                                        .description("Informe o token JWT (OIDC) emitido para o titular.")));
    }
}
