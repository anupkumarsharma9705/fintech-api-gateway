package com.fintech.fintech_gateway.auth;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AudienceValidatorTest {

    private final AudienceValidator validator = new AudienceValidator("fintech-gateway");

    @Test
    void acceptsATokenThatContainsTheAudience() {
        Jwt jwt = token(List.of("another-service", "fintech-gateway"));

        OAuth2TokenValidatorResult result = validator.validate(jwt);

        assertFalse(result.hasErrors());
    }

    @Test
    void rejectsATokenForAnotherService() {
        OAuth2TokenValidatorResult result = validator.validate(
                token(List.of("another-service")));

        assertTrue(result.hasErrors());
        assertEquals("invalid_token", result.getErrors().iterator().next().getErrorCode());
    }

    @Test
    void rejectsATokenWithoutAnAudienceClaim() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .claim("sub", "client-a")
                .build();

        assertTrue(validator.validate(jwt).hasErrors());
    }

    private Jwt token(List<String> audience) {
        return Jwt.withTokenValue("token")
                .header("alg", "none")
                .claim("aud", audience)
                .build();
    }
}
