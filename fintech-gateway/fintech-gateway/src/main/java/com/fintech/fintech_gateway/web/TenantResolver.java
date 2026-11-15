package com.fintech.fintech_gateway.web;

import com.fintech.fintech_gateway.config.GatewayProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.regex.Pattern;

/**
 * Resolves the tenant that scopes rate limits, idempotency keys and audit
 * records.
 *
 * <p>When OAuth2 authentication is enabled the tenant comes from the validated
 * token claim. The legacy header is still accepted only when it matches the
 * authenticated tenant; a mismatch is rejected instead of silently acting in
 * a different tenant. When authentication is disabled the header is trusted,
 * which is a development-only mode.</p>
 */
@Component
public class TenantResolver {

    public static final String ATTRIBUTE = "gateway.tenantId";
    public static final String AUTHENTICATED_TENANT_ATTRIBUTE =
            "gateway.authenticatedTenant";

    private final GatewayProperties properties;
    private final Pattern tenantPattern;

    public TenantResolver(GatewayProperties properties) {
        this.properties = properties;
        this.tenantPattern = Pattern.compile(properties.getTenant().getPattern());
    }

    public String resolve(HttpServletRequest request) {
        Object authenticated = request.getAttribute(AUTHENTICATED_TENANT_ATTRIBUTE);
        if (authenticated instanceof String tenant) {
            return resolveAuthenticated(request, tenant);
        }
        if (properties.getAuth().isEnabled()) {
            // Defense in depth: if the authentication filter is bypassed or
            // misordered, tenant resolution still fails closed.
            throw new GatewayRequestException(HttpStatus.UNAUTHORIZED,
                    "unauthenticated", "Client authentication is required.");
        }

        String raw = request.getHeader(properties.getTenant().getHeader());
        if (!StringUtils.hasText(raw)) {
            return properties.getTenant().getDefaultTenant();
        }
        if (!tenantPattern.matcher(raw).matches()) {
            throw new GatewayRequestException(HttpStatus.BAD_REQUEST,
                    "invalid_tenant", "The tenant header contains invalid characters.");
        }
        request.setAttribute(ATTRIBUTE, raw);
        return raw;
    }

    private String resolveAuthenticated(HttpServletRequest request, String tenant) {
        if (!tenantPattern.matcher(tenant).matches()) {
            throw new GatewayRequestException(HttpStatus.FORBIDDEN,
                    "invalid_token_claims",
                    "The access token tenant claim is not valid.");
        }
        String header = request.getHeader(properties.getTenant().getHeader());
        if (StringUtils.hasText(header) && !header.equals(tenant)) {
            throw new GatewayRequestException(HttpStatus.FORBIDDEN,
                    "tenant_mismatch",
                    "The tenant header does not match the authenticated tenant.");
        }
        request.setAttribute(ATTRIBUTE, tenant);
        return tenant;
    }

    public String get(HttpServletRequest request) {
        Object value = request.getAttribute(ATTRIBUTE);
        return value instanceof String s ? s : properties.getTenant().getDefaultTenant();
    }
}
