package com.fintech.fintech_gateway.web;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequestIdFilterTest {

    private final RequestIdFilter filter = new RequestIdFilter();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void generatesRequestIdWhenHeaderIsMissing() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/account/balance");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> { });

        String requestId = RequestIdFilter.get(request);
        assertNotNull(requestId);
        assertEquals(requestId, response.getHeader(RequestIdFilter.HEADER));
        UUID.fromString(requestId);
    }

    @Test
    void preservesAValidClientRequestId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/account/balance");
        request.addHeader(RequestIdFilter.HEADER, "client-request-123");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> { });

        assertEquals("client-request-123", RequestIdFilter.get(request));
        assertEquals("client-request-123", response.getHeader(RequestIdFilter.HEADER));
    }

    @Test
    void replacesTooShortRequestId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/account/balance");
        request.addHeader(RequestIdFilter.HEADER, "short");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> { });

        assertTrue(!RequestIdFilter.get(request).equals("short"));
        assertEquals(36, RequestIdFilter.get(request).length());
    }

    @Test
    void replacesRequestIdWithUnsafeCharacters() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/account/balance");
        request.addHeader(RequestIdFilter.HEADER, "bad id with spaces");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> { });

        assertTrue(!RequestIdFilter.get(request).contains(" "));
        assertEquals(36, RequestIdFilter.get(request).length());
    }

    @Test
    void requestIdIsAvailableInMdcDuringTheRequestAndClearedAfterwards() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/account/balance");
        request.addHeader(RequestIdFilter.HEADER, "trace-request-1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> duringRequest = new AtomicReference<>();

        filter.doFilter(request, response, (req, res) -> duringRequest.set(MDC.get("requestId")));

        assertEquals("trace-request-1", duringRequest.get());
        assertNull(MDC.get("requestId"));
    }

    @Test
    void mdcIsClearedEvenWhenTheChainFails() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/account/balance");
        MockHttpServletResponse response = new MockHttpServletResponse();

        try {
            filter.doFilter(request, response, (req, res) -> {
                throw new IllegalStateException("boom");
            });
        } catch (Exception expected) {
            // expected
        }

        assertNull(MDC.get("requestId"));
    }

    @Test
    void getReturnsEmptyStringWhenNoRequestIdWasAssigned() {
        assertEquals("", RequestIdFilter.get(new MockHttpServletRequest()));
    }
}
