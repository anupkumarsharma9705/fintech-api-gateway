package com.fintech.fintech_gateway.filter;

import com.fintech.fintech_gateway.admission.AdmissionGate;
import com.fintech.fintech_gateway.attack.AttackDetectionService;
import com.fintech.fintech_gateway.attack.AttackDetectionService.AttackDetectionResult;
import com.fintech.fintech_gateway.audit.AuditLogger;
import com.fintech.fintech_gateway.audit.AuditLogger.AuditEvent;
import com.fintech.fintech_gateway.auth.AuthenticatedClient;
import com.fintech.fintech_gateway.auth.ClientAuthenticator;
import com.fintech.fintech_gateway.config.GatewayProperties;
import com.fintech.fintech_gateway.idempotency.IdempotencyService;
import com.fintech.fintech_gateway.idempotency.IdempotencyService.StoredResponse;
import com.fintech.fintech_gateway.metrics.GatewayMetrics;
import com.fintech.fintech_gateway.ratelimit.RateLimiter;
import com.fintech.fintech_gateway.risk.RiskDecisionEngine;
import com.fintech.fintech_gateway.risk.RiskDecisionEngine.Decision;
import com.fintech.fintech_gateway.risk.RiskScoringEngine;
import com.fintech.fintech_gateway.trust.TrustTierService;
import com.fintech.fintech_gateway.trust.TrustTierService.TrustTier;
import com.fintech.fintech_gateway.web.ClientIpResolver;
import com.fintech.fintech_gateway.web.CorsPreflight;
import com.fintech.fintech_gateway.web.ErrorResponseWriter;
import com.fintech.fintech_gateway.web.GatewayRequestException;
import com.fintech.fintech_gateway.web.RequestIdFilter;
import com.fintech.fintech_gateway.web.RequestMethods;
import com.fintech.fintech_gateway.web.RequestPath;
import com.fintech.fintech_gateway.web.TenantResolver;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * The gateway's admission pipeline:
 *
 * <pre>
 * request id -> tenant -> trust tier -> sliding-window rate limit
 *   -> bounded body read -> WAF -> risk score/decision
 *   -> idempotency claim -> proxy -> audit
 * </pre>
 *
 * <p>Every failure after the pipeline starts is fail-closed. The previous
 * implementation caught all exceptions and forwarded the request anyway,
 * which turned any Redis or parsing failure into an authentication bypass.
 * It also used {@code Thread.sleep()} to throttle, pinning a Tomcat worker
 * for up to ten seconds per abusive request.</p>
 */
@Component
@Order(4)
public class RequestLoggingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);

    private final TrustTierService trustTierService;
    private final AdmissionGate admissionGate;
    private final RateLimiter rateLimiter;
    private final RiskScoringEngine riskScoringEngine;
    private final RiskDecisionEngine riskDecisionEngine;
    private final IdempotencyService idempotencyService;
    private final AttackDetectionService attackDetectionService;
    private final AuditLogger auditLogger;
    private final ClientIpResolver clientIpResolver;
    private final TenantResolver tenantResolver;
    private final RequestPath requestPath;
    private final ErrorResponseWriter errorResponseWriter;
    private final GatewayProperties properties;
    private final GatewayMetrics metrics;

    public RequestLoggingFilter(TrustTierService trustTierService,
                                AdmissionGate admissionGate,
                                RateLimiter rateLimiter,
                                RiskScoringEngine riskScoringEngine,
                                RiskDecisionEngine riskDecisionEngine,
                                IdempotencyService idempotencyService,
                                AttackDetectionService attackDetectionService,
                                AuditLogger auditLogger,
                                ClientIpResolver clientIpResolver,
                                TenantResolver tenantResolver,
                                RequestPath requestPath,
                                ErrorResponseWriter errorResponseWriter,
                                GatewayProperties properties,
                                GatewayMetrics metrics) {
        this.trustTierService = trustTierService;
        this.admissionGate = admissionGate;
        this.rateLimiter = rateLimiter;
        this.riskScoringEngine = riskScoringEngine;
        this.riskDecisionEngine = riskDecisionEngine;
        this.idempotencyService = idempotencyService;
        this.attackDetectionService = attackDetectionService;
        this.auditLogger = auditLogger;
        this.clientIpResolver = clientIpResolver;
        this.tenantResolver = tenantResolver;
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
        // CORS preflight is answered by Spring MVC and cannot execute a
        // proxied operation. With auth enabled, resolving a tenant here would
        // otherwise reject every browser preflight with 401.
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

        long startNanos = System.nanoTime();
        String requestId = RequestIdFilter.get(request);
        String ip = clientIpResolver.resolve(request);
        String method = request.getMethod();
        String uri = request.getRequestURI();
        String query = request.getQueryString();

        String tenantId;
        try {
            tenantId = tenantResolver.resolve(request);
        } catch (GatewayRequestException e) {
            reject(request, response, requestId, e.getStatus(), e.getCode(), e.getMessage(),
                    ip, method, uri, resolvedTenant(request), 0, Decision.BLOCK,
                    "INVALID_TENANT", startNanos);
            return;
        }

        Decision decision = Decision.ALLOW;
        int riskScore = 0;
        String idempotencyKey = null;
        String idempotencyOwner = null;

        try {
            // One Redis round trip: blacklist lookup, trust-tier resolution and
            // the sliding-window rate check execute atomically inside a single
            // Lua script. Previously these were three serialized calls, each
            // adding a full network round trip to every request.
            AdmissionGate.Decision admission = admissionGate.admit(tenantId, ip, requestId, 1);
            TrustTier tier = admission.tier();
            if (admission.blacklisted()) {
                reject(request, response, requestId, HttpStatus.FORBIDDEN,
                        "access_denied", "Access permanently denied.",
                        ip, method, uri, tenantId, 100, Decision.BLOCK,
                        "BLACKLISTED", startNanos);
                return;
            }

            applyRateLimitHeaders(response, admission);
            if (!admission.allowed()) {
                metrics.recordRateLimited();
                response.setHeader("Retry-After", String.valueOf(admission.retryAfterSeconds()));
                reject(request, response, requestId, HttpStatus.TOO_MANY_REQUESTS,
                        "rate_limited", "Rate limit exceeded. Retry later.",
                        ip, method, uri, tenantId, 0, Decision.BLOCK,
                        "RATE_LIMITED", startNanos);
                return;
            }

            if (!RequestMethods.isAllowed(method)) {
                reject(request, response, requestId, HttpStatus.METHOD_NOT_ALLOWED,
                        "method_not_allowed", "HTTP method is not allowed.",
                        ip, method, uri, tenantId, 0, Decision.BLOCK,
                        "METHOD_NOT_ALLOWED", startNanos);
                return;
            }

            HttpServletRequest effectiveRequest = request;
            String body = "";
            if (hasRequestBody(method)) {
                String contentEncoding = request.getHeader("Content-Encoding");
                if (StringUtils.hasText(contentEncoding)
                        && !"identity".equalsIgnoreCase(contentEncoding.trim())) {
                    // The WAF inspects plaintext. Decompressing attacker-supplied
                    // data safely (bounded, no zip bombs) is not implemented, and
                    // forwarding compressed bytes without the encoding header
                    // would corrupt the request. Reject instead of guessing.
                    reject(request, response, requestId, HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                            "unsupported_content_encoding",
                            "Compressed request bodies are not supported.",
                            ip, method, uri, tenantId, 0, Decision.BLOCK,
                            "UNSUPPORTED_CONTENT_ENCODING", startNanos);
                    return;
                }
                byte[] bytes = CachedBodyRequestWrapper.readBounded(
                        request, properties.getSecurity().getMaxBodyBytes());
                effectiveRequest = new CachedBodyRequestWrapper(request, bytes);
                body = new String(bytes, StandardCharsets.UTF_8);
            }

            AttackDetectionResult attack = attackDetectionService.inspect(body, uri, query);
            if (attack.isThreatDetected()) {
                metrics.recordWafBlock(attack.getThreatType());
                trustTierService.recordBlock(ip);
                reject(request, response, requestId, HttpStatus.FORBIDDEN,
                        "request_rejected", "Request rejected by security policy.",
                        ip, method, uri, tenantId, 100, Decision.BLOCK,
                        attack.getThreatType(), startNanos);
                return;
            }

            riskScore = riskScoringEngine.calculateScore(effectiveRequest, tier);
            decision = riskDecisionEngine.decide(riskScore);

            if (decision == Decision.BLOCK) {
                trustTierService.recordBlock(ip);
                reject(request, response, requestId, HttpStatus.FORBIDDEN,
                        "request_blocked", "Request rejected by risk policy.",
                        ip, method, uri, tenantId, riskScore, Decision.BLOCK,
                        null, startNanos);
                return;
            }

            if (decision == Decision.THROTTLE) {
                RateLimiter.Decision penalty = rateLimiter.check(tenantId, ip, tier,
                        requestId + ":throttle", properties.getRateLimit().getThrottleCost());
                applyRateLimitHeaders(response, penalty);
                trustTierService.recordThrottle(ip);
                if (!penalty.allowed()) {
                    metrics.recordRateLimited();
                    response.setHeader("Retry-After", String.valueOf(penalty.retryAfterSeconds()));
                    reject(request, response, requestId, HttpStatus.TOO_MANY_REQUESTS,
                            "throttled", "Request throttled. Retry later.",
                            ip, method, uri, tenantId, riskScore, Decision.BLOCK,
                            "THROTTLE_EXCEEDED", startNanos);
                    return;
                }
                response.setHeader("X-Gateway-Throttled", "true");
            }

            boolean unsafe = isUnsafe(method);
            boolean idempotencyRequired = unsafe && properties.getIdempotency().isRequiredFor(uri);
            if (unsafe && (idempotencyRequired || StringUtils.hasText(request.getHeader("Idempotency-Key")))) {
                idempotencyKey = request.getHeader("Idempotency-Key");
                if (!StringUtils.hasText(idempotencyKey)) {
                    reject(request, response, requestId, HttpStatus.BAD_REQUEST,
                            "idempotency_key_required",
                            "An Idempotency-Key header is required for this operation.",
                            ip, method, uri, tenantId, riskScore, decision,
                            "IDEMPOTENCY_KEY_REQUIRED", startNanos);
                    return;
                }
                idempotencyService.validateKey(idempotencyKey);
                IdempotencyService.Result idempotency =
                        idempotencyService.acquire(tenantId, idempotencyKey);

                if (idempotency.state() == IdempotencyService.Acquisition.COMPLETED) {
                    metrics.recordIdempotencyReplay();
                    StoredResponse stored = idempotency.response();
                    response.setStatus(stored.status());
                    if (StringUtils.hasText(stored.contentType())) {
                        response.setContentType(stored.contentType());
                    }
                    response.setHeader("Idempotency-Replayed", "true");
                    response.setHeader("Cache-Control", "no-store");
                    if (StringUtils.hasText(stored.contentEncoding())) {
                        response.setHeader("Content-Encoding", stored.contentEncoding());
                    }
                    byte[] storedBody = stored.body();
                    if (storedBody != null && storedBody.length > 0) {
                        response.setContentLength(storedBody.length);
                        response.getOutputStream().write(storedBody);
                    }
                    audit(request, ip, method, uri, tenantId, requestId, riskScore, decision,
                            "DUPLICATE", stored.status(), startNanos);
                    return;
                }
                if (idempotency.state() == IdempotencyService.Acquisition.IN_PROGRESS) {
                    response.setHeader("Retry-After", "1");
                    reject(request, response, requestId, HttpStatus.CONFLICT,
                            "request_in_progress",
                            "A request with this Idempotency-Key is already in progress.",
                            ip, method, uri, tenantId, riskScore, decision,
                            "IDEMPOTENCY_IN_PROGRESS", startNanos);
                    return;
                }
                idempotencyOwner = idempotency.ownerToken();
            }

            CachedResponseWrapper wrapped = idempotencyOwner != null
                    ? new CachedResponseWrapper(response)
                    : null;

            try {
                filterChain.doFilter(effectiveRequest, wrapped != null ? wrapped : response);
            } catch (Exception e) {
                if (idempotencyOwner != null) {
                    releaseIdempotency(tenantId, idempotencyKey, idempotencyOwner, requestId);
                }
                throw e;
            }

            int status = response.getStatus();
            if (wrapped != null) {
                if (status >= 200 && status < 300) {
                    completeIdempotency(tenantId, idempotencyKey, idempotencyOwner,
                            status, response, wrapped, requestId);
                } else {
                    releaseIdempotency(tenantId, idempotencyKey, idempotencyOwner, requestId);
                }
                wrapped.copyBodyToResponse();
            }

            if (decision == Decision.ALLOW && status >= 200 && status < 400) {
                trustTierService.recordSuccess(ip, tier);
            }

            audit(request, ip, method, uri, tenantId, requestId, riskScore, decision,
                    null, status, startNanos);
            metrics.recordRequest(decision, status, System.nanoTime() - startNanos);

        } catch (GatewayRequestException e) {
            if (e.getStatus().is5xxServerError()) {
                metrics.recordSecurityDependencyFailure();
            }
            reject(request, response, requestId, e.getStatus(), e.getCode(), e.getMessage(),
                    ip, method, uri, tenantId, riskScore, Decision.BLOCK,
                    e.getStatus().is5xxServerError() ? "SECURITY_DEPENDENCY" : null, startNanos);
        } catch (DataAccessException e) {
            log.error("Security dependency failed; rejecting request {}", requestId, e);
            metrics.recordSecurityDependencyFailure();
            reject(request, response, requestId, HttpStatus.SERVICE_UNAVAILABLE,
                    "security_dependency_unavailable",
                    "Security checks are temporarily unavailable. Retry shortly.",
                    ip, method, uri, tenantId, riskScore, Decision.BLOCK,
                    "SECURITY_DEPENDENCY", startNanos);
        } catch (Exception e) {
            log.error("Gateway pipeline failed; rejecting request {}", requestId, e);
            reject(request, response, requestId, HttpStatus.SERVICE_UNAVAILABLE,
                    "gateway_unavailable",
                    "The gateway could not safely process the request. Retry shortly.",
                    ip, method, uri, tenantId, riskScore, Decision.BLOCK,
                    "GATEWAY_ERROR", startNanos);
        }
    }

    /**
     * Stores the completed response. A failure here must not replace a
     * successful upstream response: the payment has already executed, so
     * returning a gateway error would make the client retry a charge that
     * already happened. The IN_PROGRESS claim is deliberately left in place
     * so a retry cannot execute the operation again; operators get a metric
     * and a loud log line instead.
     */
    private void completeIdempotency(String tenantId, String idempotencyKey,
                                     String ownerToken, int status,
                                     HttpServletResponse response,
                                     CachedResponseWrapper wrapped, String requestId) {
        try {
            idempotencyService.complete(tenantId, idempotencyKey, ownerToken,
                    new StoredResponse(status, response.getContentType(),
                            response.getHeader("Content-Encoding"),
                            wrapped.getCapturedBody()));
        } catch (RuntimeException e) {
            log.error("Idempotent response could not be stored for request {}; "
                    + "the upstream operation succeeded but cannot be replayed",
                    requestId, e);
            metrics.recordIdempotencyStoreFailure();
        }
    }

    /**
     * Releases the claim after a failed or non-2xx execution. If Redis is
     * unavailable the key stays reserved until its TTL expires; the upstream
     * error is still returned so the caller knows the operation did not
     * succeed.
     */
    private void releaseIdempotency(String tenantId, String idempotencyKey,
                                    String ownerToken, String requestId) {
        try {
            idempotencyService.release(tenantId, idempotencyKey, ownerToken);
        } catch (RuntimeException e) {
            log.error("Idempotency claim could not be released for request {}; "
                    + "the key stays reserved until its TTL expires", requestId, e);
            metrics.recordIdempotencyReleaseFailure();
        }
    }

    private void reject(HttpServletRequest request, HttpServletResponse response,
                        String requestId, HttpStatus status, String code, String message,
                        String ip, String method, String uri, String tenantId,
                        int riskScore, Decision decision, String attackType,
                        long startNanos) throws IOException {
        if (!response.isCommitted()) {
            errorResponseWriter.write(response, status, code, message, requestId);
        }
        metrics.recordRequest(decision, status.value(), System.nanoTime() - startNanos);
        audit(request, ip, method, uri, tenantId, requestId, riskScore, decision,
                attackType, status.value(), startNanos);
    }

    private void audit(HttpServletRequest request, String ip, String method, String uri,
                       String tenantId, String requestId, int riskScore, Decision decision,
                       String attackType, int statusCode, long startNanos) {
        AuthenticatedClient client = authenticatedClient(request);
        auditLogger.log(new AuditEvent.Builder()
                .ip(ip)
                .method(method)
                .uri(uri)
                .tenantId(tenantId)
                .clientId(client != null ? client.clientId() : null)
                .requestId(requestId)
                .riskScore(riskScore)
                .decision(decision)
                .attackType(attackType)
                .statusCode(statusCode)
                .latencyMs(elapsedMillis(startNanos))
                .build());
    }

    private AuthenticatedClient authenticatedClient(HttpServletRequest request) {
        Object value = request.getAttribute(ClientAuthenticator.ATTRIBUTE);
        return value instanceof AuthenticatedClient client ? client : null;
    }

    private void applyRateLimitHeaders(HttpServletResponse response,
                                       RateLimiter.Decision decision) {
        applyRateLimitHeaders(response, decision.limit(), decision.remaining(),
                decision.retryAfterSeconds());
    }

    private void applyRateLimitHeaders(HttpServletResponse response,
                                       AdmissionGate.Decision decision) {
        applyRateLimitHeaders(response, decision.limit(), decision.remaining(),
                decision.retryAfterSeconds());
    }

    private void applyRateLimitHeaders(HttpServletResponse response, int limit,
                                       int remaining, long retryAfterSeconds) {
        response.setHeader("X-RateLimit-Limit", String.valueOf(limit));
        response.setHeader("X-RateLimit-Remaining", String.valueOf(remaining));
        long reset = retryAfterSeconds > 0
                ? retryAfterSeconds
                : Math.max(1, properties.getRateLimit().getWindow().toSeconds());
        response.setHeader("X-RateLimit-Reset", String.valueOf(reset));
    }

    private boolean hasRequestBody(String method) {
        return "POST".equalsIgnoreCase(method)
                || "PUT".equalsIgnoreCase(method)
                || "PATCH".equalsIgnoreCase(method)
                || "DELETE".equalsIgnoreCase(method);
    }

    private boolean isUnsafe(String method) {
        return hasRequestBody(method);
    }

    private String resolvedTenant(HttpServletRequest request) {
        return tenantResolver.get(request);
    }

    private long elapsedMillis(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }
}
