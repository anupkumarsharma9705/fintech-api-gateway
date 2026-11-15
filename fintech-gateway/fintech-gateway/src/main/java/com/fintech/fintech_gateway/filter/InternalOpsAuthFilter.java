package com.fintech.fintech_gateway.filter;

import com.fintech.fintech_gateway.config.GatewayProperties;
import com.fintech.fintech_gateway.persistence.PersistenceService;
import com.fintech.fintech_gateway.web.ClientIpResolver;
import com.fintech.fintech_gateway.web.CorsPreflight;
import com.fintech.fintech_gateway.web.ErrorResponseWriter;
import com.fintech.fintech_gateway.web.RequestIdFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Gates every operator endpoint ({@code /feedback/**} and
 * {@code /compliance/**}) behind a shared internal secret.
 *
 * <p>/feedback/false-positive can reverse the gateway's own blacklist and
 * trust decisions. The compliance endpoints export the audit trail. Both are
 * operator tools, not end-user endpoints. Until real operator identity/RBAC
 * exists, every caller must present a valid X-Internal-Ops-Key header.</p>
 *
 * <p>Security properties:</p>
 * <ul>
 *   <li>Fails closed when the key is not configured (an unset secret rejects
 *       everything, never allows everything).</li>
 *   <li>Compares SHA-256 digests with {@link MessageDigest#isEqual}, so the
 *       comparison is constant-time and does not leak the secret length.</li>
 *   <li>Rate-limits failed attempts per caller IP through Redis, and fails
 *       closed if Redis is unavailable.</li>
 *   <li>Writes rejected attempts to the same audit trail as every other
 *       security event.</li>
 * </ul>
 */
@Component
@Order(2)
public class InternalOpsAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(InternalOpsAuthFilter.class);
    private static final String HEADER_NAME = "X-Internal-Ops-Key";
    private static final String FAIL_COUNT_PREFIX = "opsauth:fail:";
    private static final int MAX_KEY_LENGTH = 512;

    private static final DefaultRedisScript<Long> INCREMENT_WITH_TTL = new DefaultRedisScript<>("""
            local count = redis.call('INCR', KEYS[1])
            if count == 1 then
              redis.call('EXPIRE', KEYS[1], ARGV[1])
            end
            return count
            """, Long.class);

    private final GatewayProperties properties;
    private final PersistenceService persistenceService;
    private final StringRedisTemplate redisTemplate;
    private final ClientIpResolver clientIpResolver;
    private final ErrorResponseWriter errorResponseWriter;

    public InternalOpsAuthFilter(GatewayProperties properties,
                                 PersistenceService persistenceService,
                                 StringRedisTemplate redisTemplate,
                                 ClientIpResolver clientIpResolver,
                                 ErrorResponseWriter errorResponseWriter) {
        this.properties = properties;
        this.persistenceService = persistenceService;
        this.redisTemplate = redisTemplate;
        this.clientIpResolver = clientIpResolver;
        this.errorResponseWriter = errorResponseWriter;

        if (!StringUtils.hasText(properties.getSecurity().getOpsApiKey())) {
            log.warn("gateway.security.ops-api-key is NOT configured. Every /feedback/** "
                    + "and /compliance/** request will be rejected until it is set "
                    + "(intentional fail-closed behavior).");
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !isOperatorPath(request.getRequestURI());
    }

    private boolean isOperatorPath(String path) {
        return matches(path, "/feedback") || matches(path, "/compliance");
    }

    private boolean matches(String path, String prefix) {
        return path.equals(prefix) || path.startsWith(prefix + "/");
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

        String callerIp = clientIpResolver.resolve(request);
        String requestId = RequestIdFilter.get(request);
        String lockoutKey = FAIL_COUNT_PREFIX + callerIp;

        try {
            // Browsers never send custom headers on a CORS preflight. Let the
            // MVC CORS handler answer it; the GET/POST mappings that expose
            // data or mutate state are never invoked by an OPTIONS request.
            if (CorsPreflight.isPreflight(request) && hasCorsOrigins()) {
                filterChain.doFilter(request, response);
                return;
            }

            if (isLockedOut(lockoutKey)) {
                auditReject(callerIp, request, requestId, HttpStatus.TOO_MANY_REQUESTS.value(),
                        "OPS_AUTH_LOCKOUT");
                errorResponseWriter.write(response, HttpStatus.TOO_MANY_REQUESTS,
                        "too_many_attempts",
                        "Too many failed attempts. Try again later.", requestId);
                return;
            }

            String providedKey = request.getHeader(HEADER_NAME);
            if (!credentialsMatch(providedKey)) {
                recordFailedAttempt(lockoutKey);
                log.warn("Rejected unauthorized /feedback call from {} — missing or invalid {}",
                        callerIp, HEADER_NAME);
                auditReject(callerIp, request, requestId, HttpStatus.UNAUTHORIZED.value(),
                        "UNAUTHORIZED_INTERNAL_ACCESS");
                errorResponseWriter.write(response, HttpStatus.UNAUTHORIZED,
                        "unauthorized", "Missing or invalid internal credentials.", requestId);
                return;
            }

            clearFailedAttempts(lockoutKey);
            filterChain.doFilter(request, response);
        } catch (DataAccessException e) {
            log.error("Ops authentication dependency unavailable", e);
            errorResponseWriter.write(response, HttpStatus.SERVICE_UNAVAILABLE,
                    "security_dependency_unavailable",
                    "Internal authentication is temporarily unavailable.", requestId);
        }
    }

    private boolean credentialsMatch(String providedKey) {
        String configuredKey = properties.getSecurity().getOpsApiKey();
        if (!StringUtils.hasText(configuredKey) || providedKey == null) {
            return false;
        }
        if (providedKey.length() > MAX_KEY_LENGTH) {
            return false;
        }
        byte[] expected = sha256(configuredKey);
        byte[] actual = sha256(providedKey);
        return MessageDigest.isEqual(expected, actual);
    }

    private boolean isLockedOut(String lockoutKey) {
        String count = redisTemplate.opsForValue().get(lockoutKey);
        if (count == null) {
            return false;
        }
        try {
            return Integer.parseInt(count) >= properties.getSecurity().getOpsMaxFailedAttempts();
        } catch (NumberFormatException e) {
            log.warn("Ignoring malformed ops lockout counter '{}'", count);
            return false;
        }
    }

    private void recordFailedAttempt(String lockoutKey) {
        Duration window = properties.getSecurity().getOpsLockoutWindow();
        redisTemplate.execute(INCREMENT_WITH_TTL, List.of(lockoutKey),
                String.valueOf(window.toSeconds()));
    }

    private void clearFailedAttempts(String lockoutKey) {
        redisTemplate.delete(lockoutKey);
    }

    private void auditReject(String ip, HttpServletRequest request, String requestId,
                             int statusCode, String reason) {
        persistenceService.saveAuditLog(
                Instant.now(), ip, request.getMethod(), request.getRequestURI(),
                100, "BLOCK", reason, statusCode, "internal", null, requestId, 0L);
    }

    private byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the JVM", e);
        }
    }
}
