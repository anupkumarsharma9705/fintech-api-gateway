package com.fintech.fintech_gateway.auth;

import com.fintech.fintech_gateway.config.GatewayProperties;
import com.fintech.fintech_gateway.support.JwtTestFixture;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthConfigTest {

    @Test
    void refusesToStartWithoutVerificationSource() {
        GatewayProperties properties = new GatewayProperties();

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new AuthConfig(properties, new MockEnvironment()).jwtDecoder());

        assertTrue(error.getMessage().contains("issuer-uri"));
        assertTrue(error.getMessage().contains("jwk-set-uri"));
    }

    @Test
    void refusesBlankTenantClaim() {
        GatewayProperties properties = baseProperties();
        properties.getAuth().setTenantClaim(" ");

        assertThrows(IllegalStateException.class,
                () -> new AuthConfig(properties, new MockEnvironment()).jwtDecoder());
    }

    @Test
    void refusesBlankClientIdClaim() {
        GatewayProperties properties = baseProperties();
        properties.getAuth().setClientIdClaim("");

        assertThrows(IllegalStateException.class,
                () -> new AuthConfig(properties, new MockEnvironment()).jwtDecoder());
    }

    @Test
    void refusesBlankScopeClaim() {
        GatewayProperties properties = baseProperties();
        properties.getAuth().setScopeClaim(null);

        assertThrows(IllegalStateException.class,
                () -> new AuthConfig(properties, new MockEnvironment()).jwtDecoder());
    }

    @Test
    void refusesNegativeClockSkew() {
        GatewayProperties properties = baseProperties();
        properties.getAuth().setClockSkew(Duration.ofSeconds(-1));

        assertThrows(IllegalStateException.class,
                () -> new AuthConfig(properties, new MockEnvironment()).jwtDecoder());
    }

    @Test
    void buildsDecoderWithoutAuthorizationServerDiscovery() {
        GatewayProperties properties = baseProperties();
        properties.getAuth().setJwkSetUri("http://127.0.0.1:1/jwks");

        assertNotNull(new AuthConfig(properties, new MockEnvironment()).jwtDecoder());
    }

    @Test
    void decoderValidatesSignatureIssuerAudienceAndExpiry() throws Exception {
        try (JwtTestFixture fixture = new JwtTestFixture()) {
            GatewayProperties properties = baseProperties();
            properties.getAuth().setJwkSetUri(fixture.jwkSetUri());
            properties.getAuth().setIssuerUri(JwtTestFixture.ISSUER);
            properties.getAuth().setAudience(JwtTestFixture.AUDIENCE);
            JwtDecoder decoder = new AuthConfig(properties, new MockEnvironment()).jwtDecoder();

            Jwt jwt = decoder.decode(fixture.token());

            assertEquals("tenant-a", jwt.getClaimAsString("tenant_id"));
            assertEquals("client-a", jwt.getClaimAsString("client_id"));

            assertThrows(JwtException.class,
                    () -> decoder.decode(fixture.tokenSignedWithWrongKey()));
            assertThrows(JwtException.class,
                    () -> decoder.decode(fixture.token(claims ->
                            claims.issuer("https://evil.example.test"))));
            assertThrows(JwtException.class,
                    () -> decoder.decode(fixture.token(claims ->
                            claims.claim("aud", List.of("other-service")))));
            assertThrows(JwtException.class,
                    () -> decoder.decode(fixture.token(claims ->
                            claims.expirationTime(Date.from(
                                    Instant.now().minusSeconds(600))))));
        }
    }

    @Test
    void prodProfileRefusesToDisableAuthentication() {
        GatewayProperties properties = new GatewayProperties();
        properties.getAuth().setEnabled(false);
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new AuthConfig(properties, environment).afterPropertiesSet());

        assertTrue(error.getMessage().contains("prod"));
        assertTrue(error.getMessage().contains("GATEWAY_AUTH_ENABLED"));
    }

    @Test
    void devProfileMayDisableAuthentication() {
        GatewayProperties properties = new GatewayProperties();
        properties.getAuth().setEnabled(false);
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("dev");

        assertDoesNotThrow(() -> new AuthConfig(properties, environment).afterPropertiesSet());
    }

    private GatewayProperties baseProperties() {
        GatewayProperties properties = new GatewayProperties();
        properties.getAuth().setJwkSetUri("http://127.0.0.1:1/jwks");
        return properties;
    }
}
