package com.fintech.fintech_gateway.config;

import com.fintech.fintech_gateway.support.TestProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecurityHeadersFilterTest {

    @Test
    void appliesBaselineHeadersToApiResponses() throws Exception {
        SecurityHeadersFilter filter = new SecurityHeadersFilter(TestProperties.defaults());
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(new MockHttpServletRequest("GET", "/api/account/balance"),
                response, (req, res) -> { });

        assertEquals("DENY", response.getHeader("X-Frame-Options"));
        assertEquals("nosniff", response.getHeader("X-Content-Type-Options"));
        assertEquals("no-referrer", response.getHeader("Referrer-Policy"));
        assertEquals("same-origin", response.getHeader("Cross-Origin-Opener-Policy"));
        assertEquals("same-origin", response.getHeader("Cross-Origin-Resource-Policy"));
        assertEquals("max-age=31536000; includeSubDomains",
                response.getHeader("Strict-Transport-Security"));
        assertEquals("default-src 'none'; frame-ancestors 'none'; base-uri 'none'",
                response.getHeader("Content-Security-Policy"));
    }

    @Test
    void hstsCanBeDisabledForTlsTerminatedDeployments() throws Exception {
        GatewayProperties properties = TestProperties.defaults();
        properties.getSecurity().setHstsEnabled(false);
        SecurityHeadersFilter filter = new SecurityHeadersFilter(properties);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(new MockHttpServletRequest("GET", "/api/account/balance"),
                response, (req, res) -> { });

        assertNull(response.getHeader("Strict-Transport-Security"));
    }

    @Test
    void documentationPathsKeepTheirOwnContentSecurityPolicy() throws Exception {
        SecurityHeadersFilter filter = new SecurityHeadersFilter(TestProperties.defaults());
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(new MockHttpServletRequest("GET", "/swagger-ui/index.html"),
                response, (req, res) -> { });

        assertNull(response.getHeader("Content-Security-Policy"));
    }

    @Test
    void apiDocsPathKeepsItsOwnContentSecurityPolicy() throws Exception {
        SecurityHeadersFilter filter = new SecurityHeadersFilter(TestProperties.defaults());
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(new MockHttpServletRequest("GET", "/v3/api-docs"),
                response, (req, res) -> { });

        assertNull(response.getHeader("Content-Security-Policy"));
    }

    @Test
    void operatorEndpointsAreNotCached() throws Exception {
        SecurityHeadersFilter filter = new SecurityHeadersFilter(TestProperties.defaults());
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(new MockHttpServletRequest("POST", "/feedback/false-positive"),
                response, (req, res) -> { });

        assertEquals("no-store", response.getHeader("Cache-Control"));
    }

    @Test
    void theFilterChainAlwaysRuns() throws Exception {
        SecurityHeadersFilter filter = new SecurityHeadersFilter(TestProperties.defaults());
        AtomicBoolean invoked = new AtomicBoolean(false);

        filter.doFilter(new MockHttpServletRequest("GET", "/api/account/balance"),
                new MockHttpServletResponse(), (req, res) -> invoked.set(true));

        assertTrue(invoked.get());
    }

    @Test
    void permissionsPolicyDisablesSensitiveBrowserFeatures() throws Exception {
        SecurityHeadersFilter filter = new SecurityHeadersFilter(TestProperties.defaults());
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(new MockHttpServletRequest("GET", "/api/account/balance"),
                response, (req, res) -> { });

        String policy = response.getHeader("Permissions-Policy");
        assertTrue(policy.contains("camera=()"));
        assertTrue(policy.contains("microphone=()"));
        assertTrue(policy.contains("geolocation=()"));
        assertTrue(policy.contains("payment=()"));
        assertFalse(policy.contains("*"));
    }
}
