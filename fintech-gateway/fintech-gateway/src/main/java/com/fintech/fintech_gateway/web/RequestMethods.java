package com.fintech.fintech_gateway.web;

import java.util.Set;

/**
 * HTTP methods the gateway is willing to proxy. Spring's {@code HttpMethod}
 * accepts arbitrary tokens, so an explicit allowlist is required: forwarding
 * TRACE enables cross-site tracing, and CONNECT would let a caller tunnel
 * arbitrary traffic through the gateway.
 *
 * <p>This is shared by the admission filter (which rejects and audits the
 * request) and the controller (defense in depth) so the two cannot drift.</p>
 */
public final class RequestMethods {

    private static final Set<String> ALLOWED = Set.of(
            "GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS");

    private RequestMethods() {
    }

    /**
     * Method names are case-sensitive (RFC 9110). A lowercase or unknown token
     * is not silently normalized; it is rejected.
     */
    public static boolean isAllowed(String method) {
        return method != null && ALLOWED.contains(method);
    }
}
