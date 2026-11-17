package com.fintech.fintech_gateway.feedback;

import com.fintech.fintech_gateway.support.TestProperties;
import com.fintech.fintech_gateway.trust.TrustTierService;
import com.fintech.fintech_gateway.web.GatewayRequestException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class FeedbackServiceTest {

    private static final String TENANT = "tenant-a";
    private static final String IP = "203.0.113.5";

    private TrustTierService trustTierService;
    private FeedbackService service;

    @BeforeEach
    void setUp() {
        trustTierService = mock(TrustTierService.class);
        service = new FeedbackService(trustTierService, TestProperties.defaults());
    }

    @Test
    void validFalsePositiveResetsTrustForTheNamedTenant() {
        service.handleFalsePositive(TENANT, IP, "confirmed by support");

        verify(trustTierService).resetToMedium(TENANT, IP, "confirmed by support");
    }

    @Test
    void blankReasonUsesADefaultExplanation() {
        service.handleFalsePositive(TENANT, IP, "   ");

        verify(trustTierService).resetToMedium(TENANT, IP,
                "False positive reported by ops team");
    }

    @Test
    void nullReasonUsesADefaultExplanation() {
        service.handleFalsePositive(TENANT, IP, null);

        verify(trustTierService).resetToMedium(TENANT, IP,
                "False positive reported by ops team");
    }

    @Test
    void controlCharactersAreRemovedFromTheReason() {
        service.handleFalsePositive(TENANT, IP, "line1\r\nline2\ttabbed");

        verify(trustTierService).resetToMedium(TENANT, IP, "line1  line2 tabbed");
    }

    @Test
    void overlongReasonIsTruncated() {
        String reason = "x".repeat(500);

        service.handleFalsePositive(TENANT, IP, reason);

        verify(trustTierService).resetToMedium(TENANT, IP, "x".repeat(400));
    }

    @Test
    void invalidIpIsRejectedBeforeTouchingTrustState() {
        GatewayRequestException exception = assertThrows(GatewayRequestException.class,
                () -> service.handleFalsePositive(TENANT, "not-an-ip", "reason"));

        assertEquals(400, exception.getStatus().value());
        assertEquals("invalid_ip", exception.getCode());
        verifyNoInteractions(trustTierService);
    }

    @Test
    void nullIpIsRejected() {
        assertThrows(GatewayRequestException.class,
                () -> service.handleFalsePositive(TENANT, null, "reason"));
        verify(trustTierService, never()).resetToMedium(anyString(), anyString(), anyString());
    }

    @Test
    void missingTenantIsRejected() {
        GatewayRequestException exception = assertThrows(GatewayRequestException.class,
                () -> service.handleFalsePositive(null, IP, "reason"));

        assertEquals(400, exception.getStatus().value());
        assertEquals("invalid_tenant", exception.getCode());
        verifyNoInteractions(trustTierService);
    }

    @Test
    void malformedTenantIsRejected() {
        GatewayRequestException exception = assertThrows(GatewayRequestException.class,
                () -> service.handleFalsePositive("bad tenant!", IP, "reason"));

        assertEquals(400, exception.getStatus().value());
        assertEquals("invalid_tenant", exception.getCode());
        verifyNoInteractions(trustTierService);
    }
}
