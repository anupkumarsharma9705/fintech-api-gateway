package com.fintech.fintech_gateway.auth;

import com.fintech.fintech_gateway.config.GatewayProperties;
import com.fintech.fintech_gateway.support.TestProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ClientAuthenticatorTest {

    private JwtDecoder decoder;
    private GatewayProperties properties;
    private ClientAuthenticator authenticator;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        decoder = mock(JwtDecoder.class);
        properties = TestProperties.defaults();
        properties.getAuth().setEnabled(true);
        properties.getAuth().setRequiredScopes(List.of("gateway.api"));

        ObjectProvider<JwtDecoder> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(decoder);
        authenticator = new ClientAuthenticator(provider, properties);
    }

    @Test
    void mapsValidatedClaimsToAnAuthenticatedClient() {
        when(decoder.decode("token")).thenReturn(jwt(validClaims()));

        AuthenticatedClient client = authenticator.authenticate("Bearer token");

        assertEquals("client-a", client.clientId());
        assertEquals("tenant-a", client.tenantId());
        assertEquals(Set.of("gateway.api", "payments.write"), client.scopes());
    }

    @Test
    void acceptsScopesAsACollection() {
        Map<String, Object> claims = validClaims();
        claims.put("scope", List.of("gateway.api", "payments.write"));
        when(decoder.decode("token")).thenReturn(jwt(claims));

        AuthenticatedClient client = authenticator.authenticate("Bearer token");

        assertEquals(Set.of("gateway.api", "payments.write"), client.scopes());
    }

    @Test
    void fallsBackToTheSubjectWhenClientIdClaimIsMissing() {
        Map<String, Object> claims = validClaims();
        claims.remove("client_id");
        claims.put("sub", "subject-client");
        when(decoder.decode("token")).thenReturn(jwt(claims));

        assertEquals("subject-client", authenticator.authenticate("Bearer token").clientId());
    }

    @Test
    void usesUnknownWhenNeitherClientIdNorSubjectExists() {
        Map<String, Object> claims = validClaims();
        claims.remove("client_id");
        claims.remove("sub");
        when(decoder.decode("token")).thenReturn(jwt(claims));

        assertEquals("unknown", authenticator.authenticate("Bearer token").clientId());
    }

    @Test
    void truncatesOverlongClientIdClaims() {
        Map<String, Object> claims = validClaims();
        claims.put("client_id", "c".repeat(200));
        when(decoder.decode("token")).thenReturn(jwt(claims));

        assertEquals(128, authenticator.authenticate("Bearer token").clientId().length());
    }

    @Test
    void missingTenantClaimIsForbidden() {
        Map<String, Object> claims = validClaims();
        claims.remove("tenant_id");
        when(decoder.decode("token")).thenReturn(jwt(claims));

        ClientAuthenticationException error = assertThrows(
                ClientAuthenticationException.class,
                () -> authenticator.authenticate("Bearer token"));

        assertEquals(403, error.getStatus().value());
        assertEquals("invalid_token_claims", error.getCode());
        assertEquals("client-a", error.getClientId());
    }

    @Test
    void invalidTenantClaimIsForbidden() {
        Map<String, Object> claims = validClaims();
        claims.put("tenant_id", "bad tenant!");
        when(decoder.decode("token")).thenReturn(jwt(claims));

        ClientAuthenticationException error = assertThrows(
                ClientAuthenticationException.class,
                () -> authenticator.authenticate("Bearer token"));

        assertEquals(403, error.getStatus().value());
        assertEquals("invalid_token_claims", error.getCode());
    }

    @Test
    void tenantClaimOfTheWrongTypeIsForbidden() {
        Map<String, Object> claims = validClaims();
        claims.put("tenant_id", 42);
        when(decoder.decode("token")).thenReturn(jwt(claims));

        assertEquals("invalid_token_claims", assertThrows(
                ClientAuthenticationException.class,
                () -> authenticator.authenticate("Bearer token")).getCode());
    }

    @Test
    void missingRequiredScopeIsForbidden() {
        Map<String, Object> claims = validClaims();
        claims.put("scope", "payments.write");
        when(decoder.decode("token")).thenReturn(jwt(claims));

        ClientAuthenticationException error = assertThrows(
                ClientAuthenticationException.class,
                () -> authenticator.authenticate("Bearer token"));

        assertEquals(403, error.getStatus().value());
        assertEquals("insufficient_scope", error.getCode());
        assertEquals("tenant-a", error.getTenantId());
    }

    @Test
    void missingAuthorizationHeaderIsUnauthorized() {
        ClientAuthenticationException error = assertThrows(
                ClientAuthenticationException.class,
                () -> authenticator.authenticate(null));

        assertEquals(401, error.getStatus().value());
        assertEquals("invalid_token", error.getCode());
        verifyNoInteractions(decoder);
    }

    @Test
    void nonBearerSchemeIsUnauthorized() {
        assertThrows(ClientAuthenticationException.class,
                () -> authenticator.authenticate("Basic dXNlcjpwYXNz"));
        verifyNoInteractions(decoder);
    }

    @Test
    void bearerWithoutATokenIsUnauthorized() {
        assertThrows(ClientAuthenticationException.class,
                () -> authenticator.authenticate("Bearer   "));
        verifyNoInteractions(decoder);
    }

    @Test
    void oversizedTokenIsRejectedBeforeDecoding() {
        String token = "a".repeat(8193);

        assertThrows(ClientAuthenticationException.class,
                () -> authenticator.authenticate("Bearer " + token));
        verifyNoInteractions(decoder);
    }

    @Test
    void invalidSignatureOrExpiredTokenIsUnauthorized() {
        when(decoder.decode(anyString())).thenThrow(new JwtException("bad token"));

        ClientAuthenticationException error = assertThrows(
                ClientAuthenticationException.class,
                () -> authenticator.authenticate("Bearer token"));

        assertEquals(401, error.getStatus().value());
        assertEquals("invalid_token", error.getCode());
    }

    @Test
    void networkFailureIsReportedAsDependencyUnavailable() {
        when(decoder.decode(anyString()))
                .thenThrow(new JwtException("jwks", new IOException("connection refused")));

        ClientAuthenticationException error = assertThrows(
                ClientAuthenticationException.class,
                () -> authenticator.authenticate("Bearer token"));

        assertEquals(503, error.getStatus().value());
        assertEquals("auth_dependency_unavailable", error.getCode());
    }

    @Test
    void keySourceFailureIsReportedAsDependencyUnavailable() {
        when(decoder.decode(anyString()))
                .thenThrow(new JwtException("jwks", new KeySourceException("unreachable")));

        ClientAuthenticationException error = assertThrows(
                ClientAuthenticationException.class,
                () -> authenticator.authenticate("Bearer token"));

        assertEquals(503, error.getStatus().value());
    }

    @Test
    void disabledAuthenticationCannotBeCalledDirectly() {
        properties.getAuth().setEnabled(false);

        assertThrows(IllegalStateException.class,
                () -> authenticator.authenticate("Bearer token"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void enabledAuthenticationRequiresADecoder() {
        ObjectProvider<JwtDecoder> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);

        assertThrows(IllegalStateException.class,
                () -> new ClientAuthenticator(provider, properties));
    }

    private Map<String, Object> validClaims() {
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("tenant_id", "tenant-a");
        claims.put("client_id", "client-a");
        claims.put("sub", "client-a");
        claims.put("scope", "gateway.api payments.write");
        return claims;
    }

    private Jwt jwt(Map<String, Object> claims) {
        Jwt.Builder builder = Jwt.withTokenValue("token").header("alg", "none");
        claims.forEach(builder::claim);
        return builder.build();
    }

    private static final class KeySourceException extends RuntimeException {
        private KeySourceException(String message) {
            super(message);
        }
    }
}
