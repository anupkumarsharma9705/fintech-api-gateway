package com.fintech.fintech_gateway.filter;

import com.fintech.fintech_gateway.persistence.PersistenceService;
import com.fintech.fintech_gateway.support.TestProperties;
import com.fintech.fintech_gateway.web.ClientIpResolver;
import com.fintech.fintech_gateway.web.ErrorResponseWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.ObjectMapper;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class InternalOpsAuthFilterTest {

    private static final String IP = "203.0.113.5";
    private static final String LOCKOUT_KEY = "opsauth:fail:" + IP;

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOperations;
    private PersistenceService persistenceService;
    private InternalOpsAuthFilter filter;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        persistenceService = mock(PersistenceService.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        filter = new InternalOpsAuthFilter(
                TestProperties.defaults(),
                persistenceService,
                redisTemplate,
                new ClientIpResolver(TestProperties.defaults()),
                new ErrorResponseWriter(new ObjectMapper()));
    }

    @Test
    void validKeyAllowsTheRequest() throws Exception {
        when(valueOperations.get(LOCKOUT_KEY)).thenReturn(null);
        MockHttpServletRequest request = requestWithKey("test-ops-key");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean invoked = new AtomicBoolean(false);

        filter.doFilter(request, response, (req, res) -> invoked.set(true));

        assertTrue(invoked.get());
        verify(redisTemplate).delete(LOCKOUT_KEY);
    }

    @Test
    void missingKeyIsRejectedWith401() throws Exception {
        when(valueOperations.get(LOCKOUT_KEY)).thenReturn(null);
        MockHttpServletRequest request = requestWithKey(null);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean invoked = new AtomicBoolean(false);

        filter.doFilter(request, response, (req, res) -> invoked.set(true));

        assertFalse(invoked.get());
        assertEquals(401, response.getStatus());
        assertTrue(response.getContentAsString().contains("unauthorized"));
        verify(persistenceService).saveAuditLog(any(), eq(IP), eq("POST"),
                eq("/feedback/false-positive"), eq(100), eq("BLOCK"),
                eq("UNAUTHORIZED_INTERNAL_ACCESS"), eq(401), eq("internal"),
                isNull(), anyString(), eq(0L));
    }

    @Test
    void invalidKeyIsRejectedWith401() throws Exception {
        when(valueOperations.get(LOCKOUT_KEY)).thenReturn(null);
        MockHttpServletRequest request = requestWithKey("wrong-key");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> { });

        assertEquals(401, response.getStatus());
        verify(redisTemplate).execute(any(RedisScript.class), anyList(), any(Object[].class));
    }

    @Test
    void unsetConfiguredKeyRejectsEverything() throws Exception {
        var properties = TestProperties.defaults();
        properties.getSecurity().setOpsApiKey("");
        filter = new InternalOpsAuthFilter(properties, persistenceService, redisTemplate,
                new ClientIpResolver(properties),
                new ErrorResponseWriter(new ObjectMapper()));
        when(valueOperations.get(LOCKOUT_KEY)).thenReturn(null);
        MockHttpServletRequest request = requestWithKey("");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean invoked = new AtomicBoolean(false);

        filter.doFilter(request, response, (req, res) -> invoked.set(true));

        assertFalse(invoked.get());
        assertEquals(401, response.getStatus());
    }

    @Test
    void oversizedKeyIsRejectedWithoutHashing() throws Exception {
        when(valueOperations.get(LOCKOUT_KEY)).thenReturn(null);
        MockHttpServletRequest request = requestWithKey("x".repeat(513));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> { });

        assertEquals(401, response.getStatus());
    }

    @Test
    void lockoutThresholdRejectsWith429() throws Exception {
        when(valueOperations.get(LOCKOUT_KEY)).thenReturn("10");
        MockHttpServletRequest request = requestWithKey("test-ops-key");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean invoked = new AtomicBoolean(false);

        filter.doFilter(request, response, (req, res) -> invoked.set(true));

        assertFalse(invoked.get());
        assertEquals(429, response.getStatus());
        assertTrue(response.getContentAsString().contains("too_many_attempts"));
        verify(redisTemplate, never()).execute(any(RedisScript.class), anyList(), any(Object[].class));
        verify(persistenceService).saveAuditLog(any(), eq(IP), eq("POST"),
                eq("/feedback/false-positive"), eq(100), eq("BLOCK"),
                eq("OPS_AUTH_LOCKOUT"), eq(429), eq("internal"),
                isNull(), anyString(), eq(0L));
    }

    @Test
    void attemptsBelowTheThresholdAreStillEvaluated() throws Exception {
        when(valueOperations.get(LOCKOUT_KEY)).thenReturn("3");
        MockHttpServletRequest request = requestWithKey("test-ops-key");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean invoked = new AtomicBoolean(false);

        filter.doFilter(request, response, (req, res) -> invoked.set(true));

        assertTrue(invoked.get());
    }

    @Test
    void malformedLockoutCounterDoesNotLockOutValidOperators() throws Exception {
        when(valueOperations.get(LOCKOUT_KEY)).thenReturn("not-a-number");
        MockHttpServletRequest request = requestWithKey("test-ops-key");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean invoked = new AtomicBoolean(false);

        filter.doFilter(request, response, (req, res) -> invoked.set(true));

        assertTrue(invoked.get());
    }

    @Test
    void redisFailureFailsClosedWith503() throws Exception {
        when(valueOperations.get(LOCKOUT_KEY))
                .thenThrow(new RedisConnectionFailureException("redis down"));
        MockHttpServletRequest request = requestWithKey("test-ops-key");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean invoked = new AtomicBoolean(false);

        filter.doFilter(request, response, (req, res) -> invoked.set(true));

        assertFalse(invoked.get());
        assertEquals(503, response.getStatus());
        assertTrue(response.getContentAsString().contains("security_dependency_unavailable"));
    }

    @Test
    void corsPreflightBypassesOperatorAuth() throws Exception {
        MockHttpServletRequest request =
                new MockHttpServletRequest("OPTIONS", "/feedback/false-positive");
        request.setRemoteAddr(IP);
        request.addHeader("Origin", "http://localhost:3000");
        request.addHeader("Access-Control-Request-Method", "POST");
        request.addHeader("Access-Control-Request-Headers",
                "X-Internal-Ops-Key, Content-Type");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean invoked = new AtomicBoolean(false);

        filter.doFilter(request, response, (req, res) -> invoked.set(true));

        assertTrue(invoked.get());
        verifyNoInteractions(redisTemplate);
    }

    @Test
    void nonFeedbackPathsBypassOperatorAuth() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/account/balance");
        request.setRemoteAddr(IP);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean invoked = new AtomicBoolean(false);

        filter.doFilter(request, response, (req, res) -> invoked.set(true));

        assertTrue(invoked.get());
        verifyNoInteractions(redisTemplate);
    }

    @Test
    void compliancePathsRequireTheOperatorKey() throws Exception {
        when(valueOperations.get(LOCKOUT_KEY)).thenReturn(null);
        MockHttpServletRequest request =
                new MockHttpServletRequest("GET", "/compliance/evidence/summary");
        request.setRemoteAddr(IP);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean invoked = new AtomicBoolean(false);

        filter.doFilter(request, response, (req, res) -> invoked.set(true));

        assertFalse(invoked.get());
        assertEquals(401, response.getStatus());
        verify(persistenceService).saveAuditLog(any(), eq(IP), eq("GET"),
                eq("/compliance/evidence/summary"), eq(100), eq("BLOCK"),
                eq("UNAUTHORIZED_INTERNAL_ACCESS"), eq(401), eq("internal"),
                isNull(), anyString(), eq(0L));
    }

    @Test
    void compliancePreflightBypassesOperatorAuth() throws Exception {
        MockHttpServletRequest request =
                new MockHttpServletRequest("OPTIONS", "/compliance/evidence/audit.csv");
        request.setRemoteAddr(IP);
        request.addHeader("Origin", "http://localhost:3000");
        request.addHeader("Access-Control-Request-Method", "GET");
        request.addHeader("Access-Control-Request-Headers", "X-Internal-Ops-Key");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean invoked = new AtomicBoolean(false);

        filter.doFilter(request, response, (req, res) -> invoked.set(true));

        assertTrue(invoked.get());
        verifyNoInteractions(redisTemplate);
    }

    @Test
    void plainOptionsRequestIsNotTreatedAsPreflight() throws Exception {
        when(valueOperations.get(LOCKOUT_KEY)).thenReturn(null);
        MockHttpServletRequest request =
                new MockHttpServletRequest("OPTIONS", "/feedback/false-positive");
        request.setRemoteAddr(IP);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean invoked = new AtomicBoolean(false);

        filter.doFilter(request, response, (req, res) -> invoked.set(true));

        assertFalse(invoked.get());
        assertEquals(401, response.getStatus());
    }

    @Test
    void preflightIsRejectedWhenCorsIsNotConfigured() throws Exception {
        var properties = TestProperties.defaults();
        properties.getCors().setAllowedOrigins(java.util.List.of());
        filter = new InternalOpsAuthFilter(properties, persistenceService, redisTemplate,
                new ClientIpResolver(properties),
                new ErrorResponseWriter(new ObjectMapper()));
        when(valueOperations.get(LOCKOUT_KEY)).thenReturn(null);

        MockHttpServletRequest request =
                new MockHttpServletRequest("OPTIONS", "/feedback/false-positive");
        request.setRemoteAddr(IP);
        request.addHeader("Origin", "http://localhost:3000");
        request.addHeader("Access-Control-Request-Method", "POST");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean invoked = new AtomicBoolean(false);

        filter.doFilter(request, response, (req, res) -> invoked.set(true));

        assertFalse(invoked.get());
        assertEquals(401, response.getStatus());
    }

    private MockHttpServletRequest requestWithKey(String key) {
        MockHttpServletRequest request =
                new MockHttpServletRequest("POST", "/feedback/false-positive");
        request.setRemoteAddr(IP);
        if (key != null) {
            request.addHeader("X-Internal-Ops-Key", key);
        }
        return request;
    }
}
