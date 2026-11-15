package com.fintech.fintech_gateway.auth;

import org.springframework.http.HttpStatus;

/**
 * A client-authentication failure that already carries the HTTP and audit
 * semantics the gateway needs. Keeping the mapping in one place prevents the
 * filter from turning a scope failure into a 401 or leaking decoder details
 * into a client response.
 */
public class ClientAuthenticationException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final String oauthError;
    private final String attackType;
    private final String clientId;
    private final String tenantId;

    private ClientAuthenticationException(HttpStatus status, String code,
                                          String oauthError, String attackType,
                                          String message, String clientId,
                                          String tenantId) {
        super(message);
        this.status = status;
        this.code = code;
        this.oauthError = oauthError;
        this.attackType = attackType;
        this.clientId = clientId;
        this.tenantId = tenantId;
    }

    public static ClientAuthenticationException invalidToken(String message) {
        return new ClientAuthenticationException(HttpStatus.UNAUTHORIZED,
                "invalid_token", "invalid_token", "AUTHENTICATION_FAILED",
                message, null, null);
    }

    public static ClientAuthenticationException invalidClaims(String message,
                                                              String clientId,
                                                              String tenantId) {
        return new ClientAuthenticationException(HttpStatus.FORBIDDEN,
                "invalid_token_claims", "invalid_token", "INVALID_TOKEN_CLAIMS",
                message, clientId, tenantId);
    }

    public static ClientAuthenticationException insufficientScope(String message,
                                                                  String clientId,
                                                                  String tenantId) {
        return new ClientAuthenticationException(HttpStatus.FORBIDDEN,
                "insufficient_scope", "insufficient_scope", "INSUFFICIENT_SCOPE",
                message, clientId, tenantId);
    }

    public static ClientAuthenticationException dependencyUnavailable() {
        return new ClientAuthenticationException(HttpStatus.SERVICE_UNAVAILABLE,
                "auth_dependency_unavailable", null, "AUTH_DEPENDENCY_UNAVAILABLE",
                "Client authentication is temporarily unavailable. Retry shortly.",
                null, null);
    }

    public HttpStatus getStatus() { return status; }
    public String getCode() { return code; }
    public String getOauthError() { return oauthError; }
    public String getAttackType() { return attackType; }
    public String getClientId() { return clientId; }
    public String getTenantId() { return tenantId; }
}
