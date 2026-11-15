package com.fintech.fintech_gateway.auth;

import com.fintech.fintech_gateway.audit.AuditLogger;
import com.fintech.fintech_gateway.audit.AuditLogger.AuditEvent;
import com.fintech.fintech_gateway.config.GatewayProperties;
import com.fintech.fintech_gateway.metrics.GatewayMetrics;
import com.fintech.fintech_gateway.risk.RiskDecisionEngine.Decision;
import com.fintech.fintech_gateway.support.TestProperties;
import com.fintech.fintech_gateway.web.ClientIpResolver;
import com.fintech.fintech_gateway.web.ErrorResponseWriter;
import com.fintech.fintech_gateway.web.RequestIdFilter;
import com.fintech.fintech_gateway.web.RequestPath;
import com.fintech.fintech_gateway.web.TenantResolver;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ClientAuthFilterTest {

    private static final String IP = "203.0.113.5";
    private static final String REQUEST_ID = "req-12345678";

    private ClientAuthenticator authenticator;
    private AuditLogger auditLogger;
    private GatewayProperties properties;
    private SimpleMeterRegistry registry;
    private ClientAuthFilter filter;

    @BeforeEach
    void setUp() {
        authenticator = mock(ClientAuthenticator.class);
        auditLogger = mock(AuditLogger.class);
        properties = TestProperties.defaults();
        properties.getAuth().setEnabled(true);
        properties.getAuth().setRequiredScopes(List.of("gateway.api"));
        registry = new SimpleMeterRegistry();
        filter = new ClientAuthFilter(
                authenticator,
                auditLogger,
                new ClientIpResolver(properties),
                new RequestPath(),
                new ErrorResponseWriter(new ObjectMapper()),
                properties,
                new GatewayMetrics(registry));
    }

    @Test
    void validTokenSetsAuthenticationAttributesAndContinues() throws Exception {
        AuthenticatedClient client =
                new AuthenticatedClient("client-a", "tenant-a", Set.of("gateway.api"));
        when(authenticator.authenticate("Bearer token")).thenReturn(client);
        MockHttpServletRequest request = request("GET", "/api/account/balance");
        request.addHeader("Authorization", "Bearer token");
        AtomicBoolean invoked = new AtomicBoolean(false);

        filter.doFilter(request, new MockHttpServletResponse(),
                (req, res) -> invoked.set(true));

        assertTrue(invoked.get());
        assertSame(client, request.getAttribute(ClientAuthenticator.ATTRIBUTE));
        assertEquals("tenant-a",
                request.getAttribute(TenantResolver.AUTHENTICATED_TENANT_ATTRIBUTE));
        verifyNoInteractions(auditLogger);
        assertNull(registry.find("gateway.auth.failures").counter());
    }

    @Test
    void invalidTokenReturns401WithBearerChallenge() throws Exception {
        when(authenticator.authenticate(any()))
                .thenThrow(ClientAuthenticationException.invalidToken("bad token"));
        MockHttpServletRequest request = request("GET", "/api/account/balance");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean invoked = new AtomicBoolean(false);

        filter.doFilter(request, response, (req, res) -> invoked.set(true));

        assertFalse(invoked.get());
        assertEquals(401, response.getStatus());
        assertEquals("Bearer realm=\"fintech-gateway\", error=\"invalid_token\"",
                response.getHeader("WWW-Authenticate"));
        assertTrue(response.getContentAsString().contains("\"error\":\"invalid_token\""));

        AuditEvent event = capturedAuditEvent();
        assertEquals("AUTHENTICATION_FAILED", event.getAttackType());
        assertEquals(Decision.BLOCK, event.getDecision());
        assertEquals("unknown", event.getTenantId());
        assertNull(event.getClientId());
        assertEquals(1.0, registry.get("gateway.auth.failures")
                .tag("reason", "AUTHENTICATION_FAILED").counter().count());
    }

    @Test
    void invalidClaimsReturn403AndAuditTheClientAndTenant() throws Exception {
        when(authenticator.authenticate(any()))
                .thenThrow(ClientAuthenticationException.invalidClaims(
                        "missing tenant", "client-a", null));
        MockHttpServletRequest request = request("GET", "/api/account/balance");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> { });

        assertEquals(403, response.getStatus());
        assertTrue(response.getContentAsString().contains("\"error\":\"invalid_token_claims\""));
        AuditEvent event = capturedAuditEvent();
        assertEquals("INVALID_TOKEN_CLAIMS", event.getAttackType());
        assertEquals("client-a", event.getClientId());
    }

    @Test
    void insufficientScopeReturns403WithScopeChallenge() throws Exception {
        when(authenticator.authenticate(any()))
                .thenThrow(ClientAuthenticationException.insufficientScope(
                        "missing scope", "client-a", "tenant-a"));
        MockHttpServletRequest request = request("GET", "/api/account/balance");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> { });

        assertEquals(403, response.getStatus());
        assertEquals("Bearer realm=\"fintech-gateway\", error=\"insufficient_scope\", "
                        + "scope=\"gateway.api\"",
                response.getHeader("WWW-Authenticate"));
        AuditEvent event = capturedAuditEvent();
        assertEquals("INSUFFICIENT_SCOPE", event.getAttackType());
        assertEquals("tenant-a", event.getTenantId());
        assertEquals("client-a", event.getClientId());
    }

    @Test
    void dependencyFailureReturns503WithoutABearerChallenge() throws Exception {
        when(authenticator.authenticate(any()))
                .thenThrow(ClientAuthenticationException.dependencyUnavailable());
        MockHttpServletRequest request = request("GET", "/api/account/balance");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> { });

        assertEquals(503, response.getStatus());
        assertNull(response.getHeader("WWW-Authenticate"));
        assertTrue(response.getContentAsString()
                .contains("\"error\":\"auth_dependency_unavailable\""));
        assertEquals("AUTH_DEPENDENCY_UNAVAILABLE", capturedAuditEvent().getAttackType());
    }

    @Test
    void unexpectedRuntimeFailureFailsClosedWith503() throws Exception {
        when(authenticator.authenticate(any()))
                .thenThrow(new IllegalStateException("decoder exploded"));
        MockHttpServletRequest request = request("GET", "/api/account/balance");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> { });

        assertEquals(503, response.getStatus());
        assertEquals("AUTH_DEPENDENCY_UNAVAILABLE", capturedAuditEvent().getAttackType());
    }

    @Test
    void internalOperatorAndHealthPathsBypassClientAuthentication() throws Exception {
        for (String path : List.of("/health", "/feedback/false-positive",
                "/compliance/evidence/summary", "/actuator/health")) {
            MockHttpServletRequest request = request("GET", path);
            AtomicBoolean invoked = new AtomicBoolean(false);

            filter.doFilter(request, new MockHttpServletResponse(),
                    (req, res) -> invoked.set(true));

            assertTrue(invoked.get(), path);
        }
        verifyNoInteractions(authenticator, auditLogger);
    }

    @Test
    void corsPreflightBypassesClientAuthentication() throws Exception {
        MockHttpServletRequest request = request("OPTIONS", "/api/payments");
        request.addHeader("Origin", "http://localhost:3000");
        request.addHeader("Access-Control-Request-Method", "POST");
        AtomicBoolean invoked = new AtomicBoolean(false);

        filter.doFilter(request, new MockHttpServletResponse(),
                (req, res) -> invoked.set(true));

        assertTrue(invoked.get());
        verifyNoInteractions(authenticator, auditLogger);
    }

    @Test
    void plainOptionsRequestStillRequiresAuthentication() throws Exception {
        when(authenticator.authenticate(any()))
                .thenThrow(ClientAuthenticationException.invalidToken("missing token"));
        MockHttpServletRequest request = request("OPTIONS", "/api/payments");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean invoked = new AtomicBoolean(false);

        filter.doFilter(request, response, (req, res) -> invoked.set(true));

        assertFalse(invoked.get());
        assertEquals(401, response.getStatus());
    }

    @Test
    void preflightDoesNotBypassAuthenticationWhenCorsIsNotConfigured() throws Exception {
        properties.getCors().setAllowedOrigins(List.of());
        when(authenticator.authenticate(any()))
                .thenThrow(ClientAuthenticationException.invalidToken("missing token"));
        MockHttpServletRequest request = request("OPTIONS", "/api/payments");
        request.addHeader("Origin", "http://localhost:3000");
        request.addHeader("Access-Control-Request-Method", "POST");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> { });

        assertEquals(401, response.getStatus());
    }

    @Test
    void disabledAuthenticationPassesRequestsThrough() throws Exception {
        properties.getAuth().setEnabled(false);
        MockHttpServletRequest request = request("GET", "/api/account/balance");
        AtomicBoolean invoked = new AtomicBoolean(false);

        filter.doFilter(request, new MockHttpServletResponse(),
                (req, res) -> invoked.set(true));

        assertTrue(invoked.get());
        verifyNoInteractions(authenticator, auditLogger);
    }

    private MockHttpServletRequest request(String method, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setRemoteAddr(IP);
        request.setAttribute(RequestIdFilter.ATTRIBUTE, REQUEST_ID);
        return request;
    }

    private AuditEvent capturedAuditEvent() {
        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditLogger).log(captor.capture());
        return captor.getValue();
    }
}
