package com.fintech.fintech_gateway.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(prefix = "springdoc.api-docs", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class OpenApiConfig {

    @Bean
    public OpenAPI gatewayOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Fintech Gateway API")
                        .version("1.0.0")
                        .description("""
                                Adaptive API security gateway for fintech workloads.
                                Client traffic is proxied transparently; the documented
                                endpoints are the operator-facing control surface.
                                """))
                .components(new Components()
                        .addSecuritySchemes("internalOpsKey", new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER)
                                .name("X-Internal-Ops-Key"))
                        .addSecuritySchemes("bearerAuth", new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("""
                                        OAuth2 client-credentials access token. Signature,
                                        issuer, audience and expiry are validated by the
                                        gateway; the tenant and client identity come from
                                        signed token claims, not request headers.
                                        """)));
    }
}
