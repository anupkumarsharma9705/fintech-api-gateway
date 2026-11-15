package com.fintech.fintech_gateway.auth;

import com.fintech.fintech_gateway.config.GatewayProperties;
import com.fintech.fintech_gateway.config.GatewayProperties.Auth;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Validates OAuth2 client-credentials access tokens and maps their claims to
 * a tenant. Signature, issuer, audience and timestamp validation happen in
 * the {@link JwtDecoder}; this class enforces the gateway's own claim
 * contract (tenant, client id and required scopes).
 */
@Component
public class ClientAuthenticator {

    private static final Logger log = LoggerFactory.getLogger(ClientAuthenticator.class);

    public static final String ATTRIBUTE = "gateway.authenticatedClient";

    private static final int MAX_TOKEN_LENGTH = 8192;
    private static final int MAX_CLIENT_ID_LENGTH = 128;
    private static final int MAX_TENANT_LENGTH = 64;

    private final JwtDecoder jwtDecoder;
    private final GatewayProperties properties;
    private final Pattern tenantPattern;

    public ClientAuthenticator(ObjectProvider<JwtDecoder> decoderProvider,
                               GatewayProperties properties) {
        this.jwtDecoder = decoderProvider.getIfAvailable();
        this.properties = properties;
        this.tenantPattern = Pattern.compile(properties.getTenant().getPattern());
        if (properties.getAuth().isEnabled() && jwtDecoder == null) {
            throw new IllegalStateException(
                    "gateway.auth.enabled=true but no JwtDecoder bean is available. "
                            + "Configure gateway.auth.issuer-uri or gateway.auth.jwk-set-uri.");
        }
    }

    public AuthenticatedClient authenticate(String authorizationHeader) {
        Auth auth = properties.getAuth();
        if (!auth.isEnabled()) {
            throw new IllegalStateException("Client authentication is disabled");
        }
        if (jwtDecoder == null) {
            throw ClientAuthenticationException.dependencyUnavailable();
        }

        String token = extractBearerToken(authorizationHeader);
        Jwt jwt;
        try {
            jwt = jwtDecoder.decode(token);
        } catch (JwtException | IllegalArgumentException e) {
            if (isDependencyFailure(e)) {
                log.warn("JWT verification dependency unavailable: {}", e.getMessage());
                throw ClientAuthenticationException.dependencyUnavailable();
            }
            throw ClientAuthenticationException.invalidToken(
                    "The access token is missing, expired, or invalid.");
        }
        if (jwt == null) {
            throw ClientAuthenticationException.invalidToken(
                    "The access token is missing, expired, or invalid.");
        }

        String clientId = resolveClientId(jwt, auth);
        String tenantId = resolveTenant(jwt, auth, clientId);
        Set<String> scopes = resolveScopes(jwt, auth);
        requireScopes(scopes, clientId, tenantId);
        return new AuthenticatedClient(clientId, tenantId, scopes);
    }

    static String extractBearerToken(String authorizationHeader) {
        if (authorizationHeader == null || authorizationHeader.isBlank()) {
            throw ClientAuthenticationException.invalidToken(
                    "A Bearer access token is required.");
        }
        String value = authorizationHeader.trim();
        int separator = value.indexOf(' ');
        if (separator <= 0) {
            throw ClientAuthenticationException.invalidToken(
                    "The Authorization header must use the Bearer scheme.");
        }
        String scheme = value.substring(0, separator);
        if (!"Bearer".equalsIgnoreCase(scheme)) {
            throw ClientAuthenticationException.invalidToken(
                    "The Authorization header must use the Bearer scheme.");
        }
        String token = value.substring(separator + 1).trim();
        if (token.isEmpty()) {
            throw ClientAuthenticationException.invalidToken(
                    "A Bearer access token is required.");
        }
        if (token.length() > MAX_TOKEN_LENGTH) {
            throw ClientAuthenticationException.invalidToken(
                    "The access token is too large.");
        }
        return token;
    }

    private String resolveTenant(Jwt jwt, Auth auth, String clientId) {
        Object claim = jwt.getClaims().get(auth.getTenantClaim());
        if (!(claim instanceof String tenant) || tenant.isBlank()) {
            throw ClientAuthenticationException.invalidClaims(
                    "The access token does not contain the required tenant claim.",
                    clientId, null);
        }
        if (!tenantPattern.matcher(tenant).matches()) {
            throw ClientAuthenticationException.invalidClaims(
                    "The access token tenant claim is not valid.",
                    clientId, truncate(tenant, MAX_TENANT_LENGTH));
        }
        return tenant;
    }

    private String resolveClientId(Jwt jwt, Auth auth) {
        Object claim = jwt.getClaims().get(auth.getClientIdClaim());
        String clientId = claim instanceof String value && !value.isBlank()
                ? value
                : jwt.getSubject();
        if (clientId == null || clientId.isBlank()) {
            clientId = "unknown";
        }
        return truncate(clientId, MAX_CLIENT_ID_LENGTH);
    }

    private Set<String> resolveScopes(Jwt jwt, Auth auth) {
        Object claim = jwt.getClaims().get(auth.getScopeClaim());
        Set<String> scopes = new LinkedHashSet<>();
        if (claim instanceof String value) {
            for (String scope : value.trim().split("\\s+")) {
                if (!scope.isBlank()) {
                    scopes.add(scope);
                }
            }
        } else if (claim instanceof Collection<?> values) {
            for (Object value : values) {
                if (value instanceof String scope && !scope.isBlank()) {
                    scopes.add(scope);
                }
            }
        }
        return scopes;
    }

    private void requireScopes(Set<String> scopes, String clientId, String tenantId) {
        List<String> required = properties.getAuth().getRequiredScopes();
        if (required == null) {
            return;
        }
        for (String scope : required) {
            if (scope != null && !scope.isBlank() && !scopes.contains(scope.trim())) {
                throw ClientAuthenticationException.insufficientScope(
                        "The access token does not grant the required scope.",
                        clientId, tenantId);
            }
        }
    }

    private boolean isDependencyFailure(Throwable error) {
        Throwable cause = error;
        while (cause != null) {
            if (cause instanceof IOException) {
                return true;
            }
            String name = cause.getClass().getName();
            if (name.contains("KeySource") || name.contains("JWKSetRetrieval")
                    || name.contains("RemoteKeySource")) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    private String truncate(String value, int maxLength) {
        return value.length() > maxLength ? value.substring(0, maxLength) : value;
    }
}
