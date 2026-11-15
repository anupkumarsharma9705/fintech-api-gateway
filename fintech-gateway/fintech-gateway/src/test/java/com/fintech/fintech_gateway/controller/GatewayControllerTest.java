package com.fintech.fintech_gateway.controller;

import com.fintech.fintech_gateway.filter.CachedBodyRequestWrapper;
import com.fintech.fintech_gateway.proxy.ProxyService;
import com.fintech.fintech_gateway.support.TestProperties;
import com.fintech.fintech_gateway.web.ClientIpResolver;
import com.fintech.fintech_gateway.web.RequestIdFilter;
import com.fintech.fintech_gateway.web.RequestPath;
import com.fintech.fintech_gateway.web.TenantResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class GatewayControllerTest {

    private ProxyService proxyService;
    private GatewayController controller;

    @BeforeEach
    void setUp() {
        proxyService = mock(ProxyService.class);
        var properties = TestProperties.defaults();
        controller = new GatewayController(
                proxyService,
                new RequestPath(),
                properties,
                new ClientIpResolver(properties),
                new TenantResolver(properties));
    }

    @Test
    void internalPathsAreNeverForwarded() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/health");

        ResponseEntity<byte[]> response = controller.handle(request);

        assertEquals(404, response.getStatusCode().value());
        verifyNoInteractions(proxyService);
    }

    @Test
    void traceMethodIsRejectedWith405() throws Exception {
        assertMethodRejected("TRACE");
    }

    @Test
    void connectMethodIsRejectedWith405() throws Exception {
        assertMethodRejected("CONNECT");
    }

    @Test
    void unknownMethodTokenIsRejectedWith405() throws Exception {
        assertMethodRejected("BREW");
    }

    private void assertMethodRejected(String method) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, "/api/account/balance");

        ResponseEntity<byte[]> response = controller.handle(request);

        assertEquals(405, response.getStatusCode().value());
        verifyNoInteractions(proxyService);
    }

    @Test
    @SuppressWarnings("unchecked")
    void forwardsPathQueryHeadersAndCorrelationMetadata() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/account/balance");
        request.setQueryString("accountId=42");
        request.addHeader("Authorization", "Bearer token");
        request.addHeader("Accept", "application/json");
        request.setAttribute(RequestIdFilter.ATTRIBUTE, "req-12345678");
        request.setAttribute(TenantResolver.ATTRIBUTE, "tenant-a");
        request.setRemoteAddr("203.0.113.5");
        when(proxyService.forward(anyString(), any(HttpMethod.class), any(), anyMap()))
                .thenReturn(ResponseEntity.ok("{}".getBytes(StandardCharsets.UTF_8)));

        ResponseEntity<byte[]> response = controller.handle(request);

        ArgumentCaptor<Map<String, String>> headersCaptor = ArgumentCaptor.forClass(Map.class);
        verify(proxyService).forward(eq("/api/account/balance?accountId=42"),
                eq(HttpMethod.GET), any(byte[].class), headersCaptor.capture());
        Map<String, String> headers = headersCaptor.getValue();
        assertEquals("Bearer token", headers.get("Authorization"));
        assertEquals("application/json", headers.get("Accept"));
        assertEquals("req-12345678", headers.get("X-Request-Id"));
        assertEquals("tenant-a", headers.get("X-Tenant-Id"));
        assertEquals("203.0.113.5", headers.get("X-Forwarded-For"));
        assertArrayEquals("{}".getBytes(StandardCharsets.UTF_8), response.getBody());
    }

    @Test
    void forwardsTheBufferedRequestBody() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/payments");
        byte[] body = "{\"amount\":500}".getBytes(StandardCharsets.UTF_8);
        request.setAttribute(CachedBodyRequestWrapper.BODY_ATTRIBUTE, body);
        when(proxyService.forward(anyString(), any(HttpMethod.class), any(), anyMap()))
                .thenReturn(ResponseEntity.ok().build());

        controller.handle(request);

        ArgumentCaptor<byte[]> bodyCaptor = ArgumentCaptor.forClass(byte[].class);
        verify(proxyService).forward(eq("/api/payments"), eq(HttpMethod.POST),
                bodyCaptor.capture(), anyMap());
        assertArrayEquals(body, bodyCaptor.getValue());
    }

    @Test
    void absentForwardHeadersAreOmitted() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/account/balance");
        when(proxyService.forward(anyString(), any(HttpMethod.class), any(), anyMap()))
                .thenReturn(ResponseEntity.ok().build());

        controller.handle(request);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> headersCaptor = ArgumentCaptor.forClass(Map.class);
        verify(proxyService).forward(anyString(), any(HttpMethod.class), any(), headersCaptor.capture());
        assertFalse(headersCaptor.getValue().containsKey("Authorization"));
        assertTrue(headersCaptor.getValue().containsKey("X-Request-Id"));
    }

    @Test
    void upstreamResponseStatusAndBodyAreReturnedUnchanged() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/payments");
        byte[] upstreamBody = "{\"error\":\"duplicate\"}".getBytes(StandardCharsets.UTF_8);
        when(proxyService.forward(anyString(), any(HttpMethod.class), any(), anyMap()))
                .thenReturn(ResponseEntity.status(409).body(upstreamBody));

        ResponseEntity<byte[]> response = controller.handle(request);

        assertEquals(409, response.getStatusCode().value());
        assertArrayEquals(upstreamBody, response.getBody());
    }
}
