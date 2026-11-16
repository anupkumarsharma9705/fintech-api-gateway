package com.fintech.fintech_gateway.filter;

import com.fintech.fintech_gateway.admission.AdmissionGate;
import com.fintech.fintech_gateway.attack.AttackDetectionService;
import com.fintech.fintech_gateway.attack.AttackDetectionService.AttackDetectionResult;
import com.fintech.fintech_gateway.audit.AuditLogger;
import com.fintech.fintech_gateway.audit.AuditLogger.AuditEvent;
import com.fintech.fintech_gateway.config.GatewayProperties;
import com.fintech.fintech_gateway.idempotency.IdempotencyService;
import com.fintech.fintech_gateway.idempotency.IdempotencyService.StoredResponse;
import com.fintech.fintech_gateway.metrics.GatewayMetrics;
import com.fintech.fintech_gateway.ratelimit.RateLimiter;
import com.fintech.fintech_gateway.risk.RiskDecisionEngine;
import com.fintech.fintech_gateway.risk.RiskDecisionEngine.Decision;
import com.fintech.fintech_gateway.risk.RiskScoringEngine;
import com.fintech.fintech_gateway.support.TestProperties;
import com.fintech.fintech_gateway.trust.TrustTierService;
import com.fintech.fintech_gateway.trust.TrustTierService.TrustTier;
import com.fintech.fintech_gateway.web.ClientIpResolver;
import com.fintech.fintech_gateway.web.ErrorResponseWriter;
import com.fintech.fintech_gateway.web.RequestIdFilter;
import com.fintech.fintech_gateway.web.RequestPath;
import com.fintech.fintech_gateway.web.TenantResolver;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RequestLoggingFilterTest {

    private static final String IP = "203.0.113.5";
    private static final String REQUEST_ID = "req-12345678";

    private TrustTierService trustTierService;
    private AdmissionGate admissionGate;
    private RateLimiter rateLimiter;
    private RiskScoringEngine riskScoringEngine;
    private RiskDecisionEngine riskDecisionEngine;
    private IdempotencyService idempotencyService;
    private AttackDetectionService attackDetectionService;
    private AuditLogger auditLogger;
    private SimpleMeterRegistry registry;
    private GatewayProperties properties;
    private RequestLoggingFilter filter;

    @BeforeEach
    void setUp() {
        properties = TestProperties.defaults();
        trustTierService = mock(TrustTierService.class);
        admissionGate = mock(AdmissionGate.class);
        rateLimiter = mock(RateLimiter.class);
        riskScoringEngine = mock(RiskScoringEngine.class);
        riskDecisionEngine = mock(RiskDecisionEngine.class);
        idempotencyService = mock(IdempotencyService.class);
        attackDetectionService = mock(AttackDetectionService.class);
        auditLogger = mock(AuditLogger.class);
        registry = new SimpleMeterRegistry();

        filter = new RequestLoggingFilter(
                trustTierService,
                admissionGate,
                rateLimiter,
                riskScoringEngine,
                riskDecisionEngine,
                idempotencyService,
                attackDetectionService,
                auditLogger,
                new ClientIpResolver(properties),
                new TenantResolver(properties),
                new RequestPath(),
                new ErrorResponseWriter(new ObjectMapper()),
                properties,
                new GatewayMetrics(registry));

        when(admissionGate.admit(anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(new AdmissionGate.Decision(true, TrustTier.MEDIUM, false, 30, 29, 0));
        when(rateLimiter.check(anyString(), anyString(), any(TrustTier.class), anyString(), anyInt()))
                .thenReturn(new RateLimiter.Decision(true, 30, 29, 0));
        when(attackDetectionService.inspect(any(), any(), any()))
                .thenReturn(AttackDetectionResult.clean());
        when(riskScoringEngine.calculateScore(any(), any(TrustTier.class))).thenReturn(0);
        when(riskDecisionEngine.decide(anyInt())).thenReturn(Decision.ALLOW);
    }

    @Test
    void internalPathsBypassTheAdmissionPipeline() throws Exception {
        AtomicBoolean invoked = new AtomicBoolean(false);

        filter.doFilter(get("/health"), new MockHttpServletResponse(),
                (req, res) -> invoked.set(true));

        assertTrue(invoked.get());
        verifyNoInteractions(trustTierService, admissionGate, rateLimiter, attackDetectionService,
                auditLogger);
    }

    @Test
    void blacklistedCallerIsRejectedWithoutProxying() throws Exception {
        when(admissionGate.admit(anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(new AdmissionGate.Decision(false, TrustTier.BLACKLISTED, true, 0, 0, 0));
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(get("/api/account/balance"), response, chain);

        verify(chain, never()).doFilter(any(), any());
        assertEquals(403, response.getStatus());
        assertTrue(response.getContentAsString().contains("access_denied"));

        AuditEvent event = capturedAudit();
        assertEquals(IP, event.getIp());
        assertEquals("default", event.getTenantId());
        assertEquals(REQUEST_ID, event.getRequestId());
        assertEquals(100, event.getRiskScore());
        assertEquals(Decision.BLOCK, event.getDecision());
        assertEquals("BLACKLISTED", event.getAttackType());
        assertEquals(403, event.getStatusCode());
    }

    @Test
    void rateLimitedCallerIsRejectedWithRetryMetadata() throws Exception {
        when(admissionGate.admit(anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(new AdmissionGate.Decision(false, TrustTier.MEDIUM, false, 30, 0, 12));
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(get("/api/account/balance"), response, chain);

        verify(chain, never()).doFilter(any(), any());
        assertEquals(429, response.getStatus());
        assertEquals("12", response.getHeader("Retry-After"));
        assertEquals("30", response.getHeader("X-RateLimit-Limit"));
        assertEquals("0", response.getHeader("X-RateLimit-Remaining"));
        assertEquals("12", response.getHeader("X-RateLimit-Reset"));
        assertTrue(response.getContentAsString().contains("rate_limited"));
        assertEquals("RATE_LIMITED", capturedAudit().getAttackType());
        assertEquals(1.0, registry.counter("gateway.rate.limited").count());
    }

    @Test
    void disallowedMethodIsRejectedAndAuditedBeforeBodyInspection() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("PROPFIND", "/api/account/balance");
        request.setRemoteAddr(IP);
        request.setAttribute(RequestIdFilter.ATTRIBUTE, REQUEST_ID);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain, never()).doFilter(any(), any());
        verifyNoInteractions(attackDetectionService, idempotencyService);
        assertEquals(405, response.getStatus());
        assertTrue(response.getContentAsString().contains("method_not_allowed"));
        AuditEvent event = capturedAudit();
        assertEquals("PROPFIND", event.getMethod());
        assertEquals(Decision.BLOCK, event.getDecision());
        assertEquals("METHOD_NOT_ALLOWED", event.getAttackType());
        assertEquals(405, event.getStatusCode());
    }

    @Test
    void wafThreatIsRejectedAndRecorded() throws Exception {
        when(attackDetectionService.inspect(any(), any(), any()))
                .thenReturn(AttackDetectionResult.threat("SQL_INJECTION"));
        MockHttpServletRequest request = post("/api/payments", "{\"q\":\"' OR 1=1\"}");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain, never()).doFilter(any(), any());
        verify(trustTierService).recordBlock(IP);
        assertEquals(403, response.getStatus());
        assertTrue(response.getContentAsString().contains("request_rejected"));
        AuditEvent event = capturedAudit();
        assertEquals("SQL_INJECTION", event.getAttackType());
        assertEquals(100, event.getRiskScore());
        assertEquals(1.0, registry.counter("gateway.waf.blocked", "threat", "SQL_INJECTION").count());
    }

    @Test
    void highRiskRequestIsBlocked() throws Exception {
        when(riskScoringEngine.calculateScore(any(), any(TrustTier.class))).thenReturn(90);
        when(riskDecisionEngine.decide(90)).thenReturn(Decision.BLOCK);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(get("/api/account/balance"), response, chain);

        verify(chain, never()).doFilter(any(), any());
        verify(trustTierService).recordBlock(IP);
        assertEquals(403, response.getStatus());
        assertTrue(response.getContentAsString().contains("request_blocked"));
        AuditEvent event = capturedAudit();
        assertEquals(90, event.getRiskScore());
        assertEquals(Decision.BLOCK, event.getDecision());
        assertNull(event.getAttackType());
    }

    @Test
    void throttledRequestStillProxiesAndConsumesExtraBudget() throws Exception {
        when(riskScoringEngine.calculateScore(any(), any(TrustTier.class))).thenReturn(50);
        when(riskDecisionEngine.decide(50)).thenReturn(Decision.THROTTLE);
        when(rateLimiter.check(anyString(), anyString(), any(TrustTier.class), anyString(), eq(3)))
                .thenReturn(new RateLimiter.Decision(true, 30, 26, 0));
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean invoked = new AtomicBoolean(false);

        filter.doFilter(get("/api/account/balance"), response, (req, res) -> {
            invoked.set(true);
            response.setStatus(200);
        });

        assertTrue(invoked.get());
        assertEquals("true", response.getHeader("X-Gateway-Throttled"));
        verify(trustTierService).recordThrottle(IP);
        verify(rateLimiter).check(anyString(), anyString(), any(TrustTier.class),
                anyString(), eq(3));
    }

    @Test
    void exhaustedThrottleBudgetIsRejected() throws Exception {
        when(riskScoringEngine.calculateScore(any(), any(TrustTier.class))).thenReturn(50);
        when(riskDecisionEngine.decide(50)).thenReturn(Decision.THROTTLE);
        when(rateLimiter.check(anyString(), anyString(), any(TrustTier.class), anyString(), eq(3)))
                .thenReturn(new RateLimiter.Decision(false, 30, 0, 7));
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(get("/api/account/balance"), response, chain);

        verify(chain, never()).doFilter(any(), any());
        assertEquals(429, response.getStatus());
        assertEquals("7", response.getHeader("Retry-After"));
        assertTrue(response.getContentAsString().contains("throttled"));
        verify(trustTierService).recordThrottle(IP);
        assertEquals("THROTTLE_EXCEEDED", capturedAudit().getAttackType());
    }

    @Test
    void unsafePaymentWithoutIdempotencyKeyIsRejected() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(post("/api/payments/transfer", "{\"amount\":500}"), response, chain);

        verify(chain, never()).doFilter(any(), any());
        verify(idempotencyService, never()).acquire(anyString(), anyString());
        assertEquals(400, response.getStatus());
        assertTrue(response.getContentAsString().contains("idempotency_key_required"));
        assertEquals("IDEMPOTENCY_KEY_REQUIRED", capturedAudit().getAttackType());
    }

    @Test
    void compressedRequestBodiesAreRejectedBeforeWafInspection() throws Exception {
        MockHttpServletRequest request = post("/api/payments", "compressed-bytes");
        request.addHeader("Content-Encoding", "gzip");
        request.addHeader("Idempotency-Key", "key-encoded");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain, never()).doFilter(any(), any());
        verify(idempotencyService, never()).acquire(anyString(), anyString());
        assertEquals(415, response.getStatus());
        assertTrue(response.getContentAsString().contains("unsupported_content_encoding"));
        assertEquals("UNSUPPORTED_CONTENT_ENCODING", capturedAudit().getAttackType());
    }

    @Test
    void identityContentEncodingIsAccepted() throws Exception {
        MockHttpServletRequest request = post("/api/payments", "{\"amount\":500}");
        request.addHeader("Content-Encoding", "identity");
        request.addHeader("Idempotency-Key", "key-identity");
        when(idempotencyService.acquire("default", "key-identity"))
                .thenReturn(IdempotencyService.Result.acquired("owner-token"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> {
            HttpServletResponse httpResponse = (HttpServletResponse) res;
            httpResponse.setStatus(200);
            httpResponse.getWriter().write("{}");
        });

        assertEquals(200, response.getStatus());
    }

    @Test
    void completedIdempotentRequestIsReplayedFromStorage() throws Exception {
        MockHttpServletRequest request = post("/api/payments", "{\"amount\":500}");
        request.addHeader("Idempotency-Key", "key-1");
        when(idempotencyService.acquire("default", "key-1"))
                .thenReturn(IdempotencyService.Result.completed(
                        new StoredResponse(201, "application/json", null,
                                "{\"id\":\"pay_1\"}".getBytes(StandardCharsets.UTF_8))));
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain, never()).doFilter(any(), any());
        assertEquals(201, response.getStatus());
        assertEquals("application/json", response.getContentType());
        assertEquals("true", response.getHeader("Idempotency-Replayed"));
        assertEquals("no-store", response.getHeader("Cache-Control"));
        assertEquals("{\"id\":\"pay_1\"}", response.getContentAsString());
        assertEquals("DUPLICATE", capturedAudit().getAttackType());
        assertEquals(1.0, registry.counter("gateway.idempotency.replay").count());
    }

    @Test
    void concurrentIdempotentRequestConflicts() throws Exception {
        MockHttpServletRequest request = post("/api/payments", "{\"amount\":500}");
        request.addHeader("Idempotency-Key", "key-1");
        when(idempotencyService.acquire("default", "key-1"))
                .thenReturn(IdempotencyService.Result.inProgress());
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain, never()).doFilter(any(), any());
        assertEquals(409, response.getStatus());
        assertEquals("1", response.getHeader("Retry-After"));
        assertTrue(response.getContentAsString().contains("request_in_progress"));
        assertEquals("IDEMPOTENCY_IN_PROGRESS", capturedAudit().getAttackType());
    }

    @Test
    void successfulIdempotentRequestStoresTheResponseAndCopiesItToTheClient() throws Exception {
        MockHttpServletRequest request = post("/api/payments", "{\"amount\":500}");
        request.addHeader("Idempotency-Key", "key-2");
        when(idempotencyService.acquire("default", "key-2"))
                .thenReturn(IdempotencyService.Result.acquired("owner-token"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> {
            HttpServletResponse httpResponse = (HttpServletResponse) res;
            httpResponse.setStatus(201);
            httpResponse.setContentType("application/json");
            httpResponse.getWriter().write("{\"ok\":true}");
        });

        verify(idempotencyService).complete(eq("default"), eq("key-2"),
                eq("owner-token"),
                argThat(stored -> stored.status() == 201
                        && Arrays.equals("{\"ok\":true}".getBytes(StandardCharsets.UTF_8),
                                stored.body())
                        && "application/json".equals(stored.contentType())
                        && stored.contentEncoding() == null));
        verify(idempotencyService, never()).release(anyString(), anyString(), anyString());
        assertEquals(201, response.getStatus());
        assertEquals("{\"ok\":true}", response.getContentAsString());
    }

    @Test
    void successfulIdempotentRequestStoresEncodedBytesAndContentEncoding() throws Exception {
        byte[] encodedBody = new byte[]{0x1f, (byte) 0x8b, 0x08, 0x00, (byte) 0xfe};
        MockHttpServletRequest request = post("/api/payments", "{\"amount\":500}");
        request.addHeader("Idempotency-Key", "key-encoded");
        when(idempotencyService.acquire("default", "key-encoded"))
                .thenReturn(IdempotencyService.Result.acquired("owner-token"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> {
            HttpServletResponse httpResponse = (HttpServletResponse) res;
            httpResponse.setStatus(200);
            httpResponse.setContentType("application/json");
            httpResponse.setHeader("Content-Encoding", "gzip");
            httpResponse.getOutputStream().write(encodedBody);
        });

        verify(idempotencyService).complete(eq("default"), eq("key-encoded"),
                eq("owner-token"),
                argThat(stored -> stored.status() == 200
                        && "gzip".equals(stored.contentEncoding())
                        && Arrays.equals(encodedBody, stored.body())));
        assertArrayEquals(encodedBody, response.getContentAsByteArray());
    }

    @Test
    void replayedIdempotentResponsePreservesBytesAndContentEncoding() throws Exception {
        byte[] encodedBody = new byte[]{0x1f, (byte) 0x8b, 0x08, 0x00, (byte) 0xff, 0x7f};
        MockHttpServletRequest request = post("/api/payments", "{\"amount\":500}");
        request.addHeader("Idempotency-Key", "key-replay");
        when(idempotencyService.acquire("default", "key-replay"))
                .thenReturn(IdempotencyService.Result.completed(
                        new StoredResponse(201, "application/json", "gzip", encodedBody)));
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain, never()).doFilter(any(), any());
        assertEquals(201, response.getStatus());
        assertEquals("application/json", response.getContentType());
        assertEquals("gzip", response.getHeader("Content-Encoding"));
        assertEquals(encodedBody.length, response.getContentLength());
        assertArrayEquals(encodedBody, response.getContentAsByteArray(),
                "a replayed compressed response must be byte-identical");
    }

    @Test
    void successfulUpstreamResponseSurvivesIdempotencyStorageFailure() throws Exception {
        MockHttpServletRequest request = post("/api/payments", "{\"amount\":500}");
        request.addHeader("Idempotency-Key", "key-5");
        when(idempotencyService.acquire("default", "key-5"))
                .thenReturn(IdempotencyService.Result.acquired("owner-token"));
        doThrow(new RedisConnectionFailureException("redis down"))
                .when(idempotencyService).complete(anyString(), anyString(), anyString(), any());
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> {
            HttpServletResponse httpResponse = (HttpServletResponse) res;
            httpResponse.setStatus(200);
            httpResponse.setContentType("application/json");
            httpResponse.getWriter().write("{\"charged\":true}");
        });

        assertEquals(200, response.getStatus());
        assertEquals("{\"charged\":true}", response.getContentAsString());
        verify(idempotencyService, never()).release(anyString(), anyString(), anyString());
        assertEquals(1.0, registry.counter("gateway.idempotency.store.failure").count());
    }

    @Test
    void upstreamErrorSurvivesIdempotencyReleaseFailure() throws Exception {
        MockHttpServletRequest request = post("/api/payments", "{\"amount\":500}");
        request.addHeader("Idempotency-Key", "key-6");
        when(idempotencyService.acquire("default", "key-6"))
                .thenReturn(IdempotencyService.Result.acquired("owner-token"));
        doThrow(new RedisConnectionFailureException("redis down"))
                .when(idempotencyService).release("default", "key-6", "owner-token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> {
            HttpServletResponse httpResponse = (HttpServletResponse) res;
            httpResponse.setStatus(409);
            httpResponse.setContentType("application/json");
            httpResponse.getWriter().write("{\"error\":\"duplicate_payment\"}");
        });

        assertEquals(409, response.getStatus());
        assertEquals("{\"error\":\"duplicate_payment\"}", response.getContentAsString());
        assertEquals(1.0, registry.counter("gateway.idempotency.release.failure").count());
    }

    @Test
    void failedIdempotentRequestReleasesTheClaimAndStillReturnsTheUpstreamBody() throws Exception {
        MockHttpServletRequest request = post("/api/payments", "{\"amount\":500}");
        request.addHeader("Idempotency-Key", "key-3");
        when(idempotencyService.acquire("default", "key-3"))
                .thenReturn(IdempotencyService.Result.acquired("owner-token"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> {
            HttpServletResponse httpResponse = (HttpServletResponse) res;
            httpResponse.setStatus(409);
            httpResponse.setContentType("application/json");
            httpResponse.getWriter().write("{\"error\":\"duplicate_payment\"}");
        });

        verify(idempotencyService).release("default", "key-3", "owner-token");
        verify(idempotencyService, never()).complete(anyString(), anyString(), anyString(), any());
        assertEquals(409, response.getStatus());
        assertEquals("{\"error\":\"duplicate_payment\"}", response.getContentAsString());
    }

    @Test
    void chainFailureReleasesTheIdempotencyClaimAndFailsClosed() throws Exception {
        MockHttpServletRequest request = post("/api/payments", "{\"amount\":500}");
        request.addHeader("Idempotency-Key", "key-4");
        when(idempotencyService.acquire("default", "key-4"))
                .thenReturn(IdempotencyService.Result.acquired("owner-token"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> {
            throw new ServletException("downstream failure");
        });

        verify(idempotencyService).release("default", "key-4", "owner-token");
        assertEquals(503, response.getStatus());
        assertTrue(response.getContentAsString().contains("gateway_unavailable"));
    }

    @Test
    void redisFailureFailsClosedWith503() throws Exception {
        when(admissionGate.admit(anyString(), anyString(), anyString(), anyInt()))
                .thenThrow(new RedisConnectionFailureException("redis down"));
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(get("/api/account/balance"), response, chain);

        verify(chain, never()).doFilter(any(), any());
        assertEquals(503, response.getStatus());
        assertTrue(response.getContentAsString().contains("security_dependency_unavailable"));
        assertEquals("SECURITY_DEPENDENCY", capturedAudit().getAttackType());
        assertEquals(1.0, registry.counter("gateway.security.dependency.failure").count());
    }

    @Test
    void unexpectedPipelineFailureFailsClosedWith503() throws Exception {
        when(admissionGate.admit(anyString(), anyString(), anyString(), anyInt()))
                .thenThrow(new IllegalStateException("unexpected"));
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(get("/api/account/balance"), response, chain);

        verify(chain, never()).doFilter(any(), any());
        assertEquals(503, response.getStatus());
        assertTrue(response.getContentAsString().contains("gateway_unavailable"));
        assertEquals("GATEWAY_ERROR", capturedAudit().getAttackType());
    }

    @Test
    void invalidTenantIsRejectedBeforeAnySecurityLookup() throws Exception {
        MockHttpServletRequest request = get("/api/account/balance");
        request.addHeader("X-Tenant-Id", "bad tenant!");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain, never()).doFilter(any(), any());
        verifyNoInteractions(trustTierService, admissionGate, rateLimiter);
        assertEquals(400, response.getStatus());
        assertTrue(response.getContentAsString().contains("invalid_tenant"));
        AuditEvent event = capturedAudit();
        assertEquals("default", event.getTenantId());
        assertEquals("INVALID_TENANT", event.getAttackType());
    }

    @Test
    void rateLimitHeadersAreExposedOnAllowedRequests() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(get("/api/account/balance"), response, (req, res) -> response.setStatus(200));

        assertEquals("30", response.getHeader("X-RateLimit-Limit"));
        assertEquals("29", response.getHeader("X-RateLimit-Remaining"));
        assertEquals("60", response.getHeader("X-RateLimit-Reset"));
    }

    @Test
    void goodBehaviourIsRecordedAfterASuccessfulResponse() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(get("/api/account/balance"), response,
                (req, res) -> response.setStatus(200));

        verify(trustTierService).recordSuccess(IP, TrustTier.MEDIUM);
    }

    @Test
    void goodBehaviourIsNotRecordedAfterAClientError() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(get("/api/account/balance"), response,
                (req, res) -> response.setStatus(404));

        verify(trustTierService, never()).recordSuccess(anyString(), any(TrustTier.class));
    }

    @Test
    void idempotencyKeysAreScopedToTheResolvedTenant() throws Exception {
        MockHttpServletRequest request = post("/api/payments", "{\"amount\":500}");
        request.addHeader("Idempotency-Key", "key-5");
        request.addHeader("X-Tenant-Id", "tenant-a");
        when(idempotencyService.acquire("tenant-a", "key-5"))
                .thenReturn(IdempotencyService.Result.inProgress());

        filter.doFilter(request, new MockHttpServletResponse(), mock(FilterChain.class));

        verify(idempotencyService).acquire("tenant-a", "key-5");
    }

    @Test
    void oversizedBodyIsRejectedWith413BeforeTheWaf() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(post("/api/payments", "a".repeat(5000)), response, chain);

        verify(chain, never()).doFilter(any(), any());
        verifyNoInteractions(attackDetectionService);
        assertEquals(413, response.getStatus());
        assertTrue(response.getContentAsString().contains("payload_too_large"));
    }

    @Test
    void queryStringIsInspectedByTheWaf() throws Exception {
        MockHttpServletRequest request = get("/api/account/balance");
        request.setQueryString("q=clean");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> response.setStatus(200));

        verify(attackDetectionService).inspect("", "/api/account/balance", "q=clean");
    }

    @Test
    void successfulRequestIsAuditedWithRequestContext() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(get("/api/account/balance"), response,
                (req, res) -> response.setStatus(200));

        AuditEvent event = capturedAudit();
        assertEquals(REQUEST_ID, event.getRequestId());
        assertEquals("default", event.getTenantId());
        assertEquals(Decision.ALLOW, event.getDecision());
        assertEquals(200, event.getStatusCode());
        assertTrue(event.getLatencyMs() >= 0);
        assertEquals(1.0, registry.counter("gateway.requests",
                "decision", "ALLOW", "status", "200").count());
    }

    private AuditEvent capturedAudit() {
        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditLogger).log(captor.capture());
        return captor.getValue();
    }

    private MockHttpServletRequest get(String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        request.setRemoteAddr(IP);
        request.setAttribute(RequestIdFilter.ATTRIBUTE, REQUEST_ID);
        return request;
    }

    private MockHttpServletRequest post(String uri, String body) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", uri);
        request.setRemoteAddr(IP);
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        request.setContentType("application/json");
        request.setAttribute(RequestIdFilter.ATTRIBUTE, REQUEST_ID);
        return request;
    }
}
