package com.fintech.fintech_gateway.auth;

import com.fintech.fintech_gateway.audit.AuditLogger;
import com.fintech.fintech_gateway.audit.AuditLogger.AuditEvent;
import com.fintech.fintech_gateway.config.GatewayProperties;
import com.fintech.fintech_gateway.metrics.GatewayMetrics;
import com.fintech.fintech_gateway.risk.RiskDecisionEngine.Decision;
import com.fintech.fintech_gateway.web.ClientIpResolver;
import com.fintech.fintech_gateway.web.CorsPreflight;
import com.fintech.fintech_gateway.web.ErrorResponseWriter;
import com.fintech.fintech_gateway.web.RequestIdFilter;
import com.fintech.fintech_gateway.web.RequestPath;
import com.fintech.fintech_gateway.web.TenantResolver;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Authenticates every proxied API request with an OAuth2 client-credentials
 * JWT before the admission pipeline runs.
 *
 * <p>Operator paths ({@code /feedback/**}, {@code /compliance/**}) are gated by
 * {@link com.fintech.fintech_gateway.filter.InternalOpsAuthFilter} instead, and
 * CORS preflight is allowed through because a browser never attaches an
 * Authorization header to it. The filter fails closed: a missing decoder,
 * invalid signature, wrong audience, missing tenant claim or missing scope all
 * stop the request before it can reach the WAF, rate limiter or upstream.</p>
 */
@Component
@Order(3)
public class ClientAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ClientAuthFilter.class);
    private static final String REALM = "fintech-gateway";

    private final ClientAuthenticator clientAuthenticator;
    private final AuditLogger auditLogger;
    private final ClientIpResolver clientIpResolver;
    private final RequestPath requestPath;
    private final ErrorResponseWriter errorResponseWriter;
    private final GatewayProperties properties;
    private final GatewayMetrics metrics;

    public ClientAuthFilter(ClientAuthenticator clientAuthenticator,
                            AuditLogger auditLogger,
                            ClientIpResolver clientIpResolver,
                            RequestPath requestPath,
                            ErrorResponseWriter errorResponseWriter,
                            GatewayProperties properties,
                            GatewayMetrics metrics) {
        this.clientAuthenticator = clientAuthenticator;
        this.auditLogger = auditLogger;
        this.clientIpResolver = clientIpResolver;
        this.requestPath = requestPath;
        this.errorResponseWriter = errorResponseWriter;
        this.properties = properties;
        this.metrics = metrics;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (requestPath.isInternal(request.getRequestURI())) {
            return true;
        }
        // Only let a preflight through when MVC CORS handling is actually
        // configured. Without allowed origins nothing will answer it, and an
        // unauthenticated OPTIONS must not reach the proxy.
        return CorsPreflight.isPreflight(request) && hasCorsOrigins();
    }

    private boolean hasCorsOrigins() {
        return properties.getCors().getAllowedOrigins().stream()
                .anyMatch(origin -> origin != null && !origin.isBlank());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        if (!properties.getAuth().isEnabled()) {
            filterChain.doFilter(request, response);
            return;
        }

        long startNanos = System.nanoTime();
        try {
            AuthenticatedClient client = clientAuthenticator.authenticate(
                    request.getHeader(HttpHeaders.AUTHORIZATION));
            request.setAttribute(ClientAuthenticator.ATTRIBUTE, client);
            request.setAttribute(TenantResolver.AUTHENTICATED_TENANT_ATTRIBUTE,
                    client.tenantId());
            filterChain.doFilter(request, response);
        } catch (ClientAuthenticationException e) {
            handleFailure(request, response, e, startNanos);
        } catch (RuntimeException e) {
            log.error("Unexpected client-authentication failure for request {}",
                    RequestIdFilter.get(request), e);
            handleFailure(request, response,
                    ClientAuthenticationException.dependencyUnavailable(), startNanos);
        }
    }

    private void handleFailure(HttpServletRequest request, HttpServletResponse response,
                               ClientAuthenticationException e, long startNanos)
            throws IOException {
        String requestId = RequestIdFilter.get(request);
        String ip = clientIpResolver.resolve(request);
        metrics.recordAuthFailure(e.getAttackType());

        auditLogger.log(new AuditEvent.Builder()
                .ip(ip)
                .method(request.getMethod())
                .uri(request.getRequestURI())
                .tenantId(e.getTenantId() != null ? e.getTenantId() : "unknown")
                .clientId(e.getClientId())
                .requestId(requestId)
                .riskScore(100)
                .decision(Decision.BLOCK)
                .attackType(e.getAttackType())
                .statusCode(e.getStatus().value())
                .latencyMs(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos))
                .build());

        if (StringUtils.hasText(e.getOauthError())) {
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, challenge(e));
        }
        log.warn("Client authentication rejected from {}: {}", ip, e.getCode());
        errorResponseWriter.write(response, e.getStatus(), e.getCode(), e.getMessage(),
                requestId);
    }

    private String challenge(ClientAuthenticationException e) {
        StringBuilder value = new StringBuilder("Bearer realm=\"")
                .append(REALM)
                .append("\", error=\"")
                .append(safe(e.getOauthError()))
                .append('"');
        if ("insufficient_scope".equals(e.getOauthError())) {
            List<String> required = properties.getAuth().getRequiredScopes();
            if (required != null && !required.isEmpty()) {
                String scopes = required.stream()
                        .filter(StringUtils::hasText)
                        .map(String::trim)
                        .map(this::safe)
                        .reduce((a, b) -> a + " " + b)
                        .orElse("");
                if (!scopes.isEmpty()) {
                    value.append(", scope=\"").append(scopes).append('"');
                }
            }
        }
        return value.toString();
    }

    private String safe(String value) {
        return value == null ? "" : value.replace("\"", "").replace("\r", "")
                .replace("\n", "");
    }

}
