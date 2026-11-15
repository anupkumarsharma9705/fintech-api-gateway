package com.fintech.fintech_gateway.auth;

import java.util.Set;

/**
 * Identity established from a validated OAuth2 access token. The tenant id is
 * authoritative: downstream code must never re-derive it from a request
 * header when authentication is enabled.
 */
public record AuthenticatedClient(String clientId, String tenantId, Set<String> scopes) {

    public AuthenticatedClient {
        scopes = scopes == null ? Set.of() : Set.copyOf(scopes);
    }
}
