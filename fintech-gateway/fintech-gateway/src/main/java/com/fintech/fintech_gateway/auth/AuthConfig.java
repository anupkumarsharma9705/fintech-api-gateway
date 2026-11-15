package com.fintech.fintech_gateway.auth;

import com.fintech.fintech_gateway.config.GatewayProperties;
import com.fintech.fintech_gateway.config.GatewayProperties.Auth;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the JWT decoder without enabling Spring Security's servlet filter
 * chain. The gateway keeps a single explicit admission pipeline; the decoder
 * is only responsible for signature, issuer, audience and timestamp checks.
 */
@Configuration(proxyBeanMethods = false)
public class AuthConfig implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(AuthConfig.class);

    private final GatewayProperties properties;
    private final Environment environment;

    public AuthConfig(GatewayProperties properties, Environment environment) {
        this.properties = properties;
        this.environment = environment;
    }

    @Override
    public void afterPropertiesSet() {
        if (!properties.getAuth().isEnabled()) {
            if (environment != null && environment.matchesProfiles("prod")) {
                throw new IllegalStateException(
                        "gateway.auth.enabled must not be false while the prod profile is "
                                + "active. Remove GATEWAY_AUTH_ENABLED=false or run a "
                                + "non-production profile.");
            }
            log.warn("gateway.auth.enabled=false. The gateway trusts the {} header and "
                            + "does not authenticate callers. Use this only for isolated "
                            + "development or tests.",
                    properties.getTenant().getHeader());
        }
    }

    @Bean
    @ConditionalOnProperty(prefix = "gateway.auth", name = "enabled",
            havingValue = "true")
    public JwtDecoder jwtDecoder() {
        Auth auth = properties.getAuth();
        validate(auth);

        NimbusJwtDecoder decoder;
        if (hasText(auth.getJwkSetUri())) {
            // Preferred: no authorization-server metadata call during startup.
            decoder = NimbusJwtDecoder.withJwkSetUri(auth.getJwkSetUri()).build();
        } else {
            // Metadata discovery is a startup dependency on the IdP.
            decoder = NimbusJwtDecoder.withIssuerLocation(auth.getIssuerUri()).build();
        }
        decoder.setJwtValidator(validator(auth));
        return decoder;
    }

    private void validate(Auth auth) {
        if (!hasText(auth.getJwkSetUri()) && !hasText(auth.getIssuerUri())) {
            throw new IllegalStateException(
                    "gateway.auth.enabled=true requires gateway.auth.issuer-uri or "
                            + "gateway.auth.jwk-set-uri. Refusing to start without a "
                            + "token verification source.");
        }
        if (!hasText(auth.getTenantClaim())) {
            throw new IllegalStateException("gateway.auth.tenant-claim must not be blank");
        }
        if (!hasText(auth.getClientIdClaim())) {
            throw new IllegalStateException("gateway.auth.client-id-claim must not be blank");
        }
        if (!hasText(auth.getScopeClaim())) {
            throw new IllegalStateException("gateway.auth.scope-claim must not be blank");
        }
        if (auth.getClockSkew() == null || auth.getClockSkew().isNegative()) {
            throw new IllegalStateException("gateway.auth.clock-skew must be zero or positive");
        }
    }

    private OAuth2TokenValidator<Jwt> validator(Auth auth) {
        List<OAuth2TokenValidator<Jwt>> validators = new ArrayList<>();
        validators.add(new JwtTimestampValidator(auth.getClockSkew()));
        if (hasText(auth.getIssuerUri())) {
            validators.add(new JwtIssuerValidator(auth.getIssuerUri()));
        }
        if (hasText(auth.getAudience())) {
            validators.add(new AudienceValidator(auth.getAudience()));
        }
        return new DelegatingOAuth2TokenValidator<>(validators);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
