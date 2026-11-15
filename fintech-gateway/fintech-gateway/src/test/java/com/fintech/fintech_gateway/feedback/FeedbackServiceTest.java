package com.fintech.fintech_gateway.feedback;

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

    private TrustTierService trustTierService;
    private FeedbackService service;

    @BeforeEach
    void setUp() {
        trustTierService = mock(TrustTierService.class);
        service = new FeedbackService(trustTierService);
    }

    @Test
    void validFalsePositiveResetsTrust() {
        service.handleFalsePositive("203.0.113.5", "confirmed by support");

        verify(trustTierService).resetToMedium("203.0.113.5", "confirmed by support");
    }

    @Test
    void blankReasonUsesADefaultExplanation() {
        service.handleFalsePositive("203.0.113.5", "   ");

        verify(trustTierService).resetToMedium("203.0.113.5",
                "False positive reported by ops team");
    }

    @Test
    void nullReasonUsesADefaultExplanation() {
        service.handleFalsePositive("203.0.113.5", null);

        verify(trustTierService).resetToMedium("203.0.113.5",
                "False positive reported by ops team");
    }

    @Test
    void controlCharactersAreRemovedFromTheReason() {
        service.handleFalsePositive("203.0.113.5", "line1\r\nline2\ttabbed");

        verify(trustTierService).resetToMedium("203.0.113.5",
                "line1  line2 tabbed");
    }

    @Test
    void overlongReasonIsTruncated() {
        String reason = "x".repeat(500);

        service.handleFalsePositive("203.0.113.5", reason);

        verify(trustTierService).resetToMedium("203.0.113.5", "x".repeat(400));
    }

    @Test
    void invalidIpIsRejectedBeforeTouchingTrustState() {
        GatewayRequestException exception = assertThrows(GatewayRequestException.class,
                () -> service.handleFalsePositive("not-an-ip", "reason"));

        assertEquals(400, exception.getStatus().value());
        assertEquals("invalid_ip", exception.getCode());
        verifyNoInteractions(trustTierService);
    }

    @Test
    void nullIpIsRejected() {
        assertThrows(GatewayRequestException.class,
                () -> service.handleFalsePositive(null, "reason"));
        verify(trustTierService, never()).resetToMedium(anyString(), anyString());
    }
}
