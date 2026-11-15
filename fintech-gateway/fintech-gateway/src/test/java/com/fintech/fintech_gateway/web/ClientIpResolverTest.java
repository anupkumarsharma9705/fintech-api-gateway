package com.fintech.fintech_gateway.web;

import com.fintech.fintech_gateway.support.TestProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ClientIpResolverTest {

    private ClientIpResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new ClientIpResolver(TestProperties.defaults());
    }

    @Test
    void forwardedHeaderIsIgnoredWhenProxyTrustIsDisabled() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.7");
        request.addHeader("X-Forwarded-For", "203.0.113.9");

        assertEquals("10.0.0.7", resolver.resolve(request));
    }

    @Test
    void forwardedHeaderIsUsedWhenProxyTrustIsEnabled() {
        var properties = TestProperties.defaults();
        properties.getSecurity().setTrustProxy(true);
        resolver = new ClientIpResolver(properties);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.7");
        request.addHeader("X-Forwarded-For", "203.0.113.9, 10.0.0.1");

        assertEquals("203.0.113.9", resolver.resolve(request));
    }

    @Test
    void invalidForwardedValueFallsBackToRemoteAddress() {
        var properties = TestProperties.defaults();
        properties.getSecurity().setTrustProxy(true);
        resolver = new ClientIpResolver(properties);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.7");
        request.addHeader("X-Forwarded-For", "not-an-ip");

        assertEquals("10.0.0.7", resolver.resolve(request));
    }

    @Test
    void malformedIpv4ForwardedValueIsRejected() {
        var properties = TestProperties.defaults();
        properties.getSecurity().setTrustProxy(true);
        resolver = new ClientIpResolver(properties);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.7");
        request.addHeader("X-Forwarded-For", "999.1.1.1");

        assertEquals("10.0.0.7", resolver.resolve(request));
    }

    @Test
    void validIpv6ForwardedValueIsAccepted() {
        var properties = TestProperties.defaults();
        properties.getSecurity().setTrustProxy(true);
        resolver = new ClientIpResolver(properties);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.7");
        request.addHeader("X-Forwarded-For", "2001:db8::1");

        assertEquals("2001:db8::1", resolver.resolve(request));
    }

    @Test
    void missingForwardedHeaderFallsBackToRemoteAddress() {
        var properties = TestProperties.defaults();
        properties.getSecurity().setTrustProxy(true);
        resolver = new ClientIpResolver(properties);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("192.0.2.5");

        assertEquals("192.0.2.5", resolver.resolve(request));
    }
}
