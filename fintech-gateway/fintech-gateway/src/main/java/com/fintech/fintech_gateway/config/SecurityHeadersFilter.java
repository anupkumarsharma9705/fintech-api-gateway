package com.fintech.fintech_gateway.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Baseline browser-facing hardening for every response. The gateway serves
 * JSON and documentation, so a restrictive CSP is applied to API traffic
 * while documentation paths keep the assets springdoc needs.
 */
@Component
@Order(1)
public class SecurityHeadersFilter extends OncePerRequestFilter {

    private final GatewayProperties properties;

    public SecurityHeadersFilter(GatewayProperties properties) {
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("Permissions-Policy",
                "camera=(), microphone=(), geolocation=(), payment=()");
        response.setHeader("Cross-Origin-Opener-Policy", "same-origin");
        response.setHeader("Cross-Origin-Resource-Policy", "same-origin");

        if (properties.getSecurity().isHstsEnabled()) {
            response.setHeader("Strict-Transport-Security",
                    "max-age=31536000; includeSubDomains");
        }

        String path = request.getRequestURI();
        if (!isDocumentationPath(path)) {
            response.setHeader("Content-Security-Policy",
                    "default-src 'none'; frame-ancestors 'none'; base-uri 'none'");
        }
        if (path.startsWith("/feedback")) {
            response.setHeader("Cache-Control", "no-store");
        }

        filterChain.doFilter(request, response);
    }

    private boolean isDocumentationPath(String path) {
        return path.startsWith("/swagger-ui")
                || path.startsWith("/v3/api-docs")
                || path.equals("/swagger-ui.html");
    }
}
