package com.fintech.fintech_gateway.web;

import com.fintech.fintech_gateway.config.GatewayProperties;
import com.fintech.fintech_gateway.support.TestProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TenantResolverTest {

    private TenantResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new TenantResolver(TestProperties.defaults());
    }

    @Test
    void missingHeaderUsesDefaultTenant() {
        assertEquals("default", resolver.resolve(new MockHttpServletRequest()));
    }

    @Test
    void blankHeaderUsesDefaultTenant() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Tenant-Id", "   ");
        assertEquals("default", resolver.resolve(request));
    }

    @Test
    void validTenantIsReturnedAndStoredAsAttribute() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Tenant-Id", "tenant_42");

        assertEquals("tenant_42", resolver.resolve(request));
        assertEquals("tenant_42", resolver.get(request));
        assertEquals("tenant_42", request.getAttribute(TenantResolver.ATTRIBUTE));
    }

    @Test
    void invalidCharactersAreRejected() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Tenant-Id", "bad tenant!");

        GatewayRequestException exception = assertThrows(GatewayRequestException.class,
                () -> resolver.resolve(request));

        assertEquals(400, exception.getStatus().value());
        assertEquals("invalid_tenant", exception.getCode());
    }

    @Test
    void overlyLongTenantIsRejected() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Tenant-Id", "a".repeat(65));

        assertThrows(GatewayRequestException.class, () -> resolver.resolve(request));
    }

    @Test
    void getFallsBackToDefaultWhenNothingWasResolved() {
        assertEquals("default", resolver.get(new MockHttpServletRequest()));
    }

    @Test
    void authenticatedTenantIsUsedWhenAuthIsEnabled() {
        TenantResolver authenticatedResolver = resolverWithAuth(true);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(TenantResolver.AUTHENTICATED_TENANT_ATTRIBUTE, "tenant_a");

        assertEquals("tenant_a", authenticatedResolver.resolve(request));
        assertEquals("tenant_a", request.getAttribute(TenantResolver.ATTRIBUTE));
    }

    @Test
    void matchingTenantHeaderIsAcceptedWithAuthentication() {
        TenantResolver authenticatedResolver = resolverWithAuth(true);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(TenantResolver.AUTHENTICATED_TENANT_ATTRIBUTE, "tenant_a");
        request.addHeader("X-Tenant-Id", "tenant_a");

        assertEquals("tenant_a", authenticatedResolver.resolve(request));
    }

    @Test
    void mismatchedTenantHeaderIsRejectedWith403() {
        TenantResolver authenticatedResolver = resolverWithAuth(true);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(TenantResolver.AUTHENTICATED_TENANT_ATTRIBUTE, "tenant_a");
        request.addHeader("X-Tenant-Id", "tenant_b");

        GatewayRequestException exception = assertThrows(GatewayRequestException.class,
                () -> authenticatedResolver.resolve(request));

        assertEquals(403, exception.getStatus().value());
        assertEquals("tenant_mismatch", exception.getCode());
    }

    @Test
    void invalidAuthenticatedTenantClaimIsRejectedWith403() {
        TenantResolver authenticatedResolver = resolverWithAuth(true);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(TenantResolver.AUTHENTICATED_TENANT_ATTRIBUTE, "bad tenant!");

        GatewayRequestException exception = assertThrows(GatewayRequestException.class,
                () -> authenticatedResolver.resolve(request));

        assertEquals(403, exception.getStatus().value());
        assertEquals("invalid_token_claims", exception.getCode());
    }

    @Test
    void authEnabledWithoutAuthenticatedAttributeFailsClosed() {
        TenantResolver authenticatedResolver = resolverWithAuth(true);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Tenant-Id", "tenant_a");

        GatewayRequestException exception = assertThrows(GatewayRequestException.class,
                () -> authenticatedResolver.resolve(request));

        assertEquals(401, exception.getStatus().value());
        assertEquals("unauthenticated", exception.getCode());
    }

    private TenantResolver resolverWithAuth(boolean enabled) {
        GatewayProperties properties = TestProperties.defaults();
        properties.getAuth().setEnabled(enabled);
        return new TenantResolver(properties);
    }
}
