package com.fintech.fintech_gateway.risk;

import com.fintech.fintech_gateway.support.TestProperties;
import com.fintech.fintech_gateway.trust.TrustTierService.TrustTier;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RiskScoringEngineTest {

    private static final Clock BUSINESS_HOURS =
            Clock.fixed(Instant.parse("2026-01-15T10:00:00Z"), ZoneOffset.UTC);
    private static final Clock OFF_HOURS =
            Clock.fixed(Instant.parse("2026-01-15T23:30:00Z"), ZoneOffset.UTC);

    private final RiskScoringEngine engine = new RiskScoringEngine(BUSINESS_HOURS, TestProperties.defaults());

    @Test
    void readBalanceScoresEndpointSensitivity() {
        MockHttpServletRequest request = request("GET", "/api/account/balance", 0);
        assertEquals(10, engine.calculateScore(request, TrustTier.MEDIUM));
    }

    @Test
    void writePaymentScoresHighestEndpointSensitivity() {
        MockHttpServletRequest request = request("POST", "/api/payments/transfer", 100);
        assertEquals(30, engine.calculateScore(request, TrustTier.MEDIUM));
    }

    @Test
    void largePayloadAddsRisk() {
        MockHttpServletRequest request = request("POST", "/api/payments/transfer", 20_000);
        assertEquals(40, engine.calculateScore(request, TrustTier.MEDIUM));
    }

    @Test
    void veryLargePayloadAddsMoreRisk() {
        MockHttpServletRequest request = request("POST", "/api/payments/transfer", 200_000);
        assertEquals(50, engine.calculateScore(request, TrustTier.MEDIUM));
    }

    @Test
    void offHoursAddsRisk() {
        RiskScoringEngine offHoursEngine = new RiskScoringEngine(OFF_HOURS, TestProperties.defaults());
        MockHttpServletRequest request = request("GET", "/api/account/balance", 0);
        assertEquals(25, offHoursEngine.calculateScore(request, TrustTier.MEDIUM));
    }

    @Test
    void highTrustReducesScore() {
        MockHttpServletRequest request = request("GET", "/api/account/balance", 0);
        assertEquals(0, engine.calculateScore(request, TrustTier.HIGH));
    }

    @Test
    void lowTrustIncreasesScore() {
        MockHttpServletRequest request = request("GET", "/api/account/balance", 0);
        assertEquals(30, engine.calculateScore(request, TrustTier.LOW));
    }

    @Test
    void blacklistedTrustIsClampedAtMaximum() {
        MockHttpServletRequest request = request("GET", "/api/account/balance", 0);
        assertEquals(100, engine.calculateScore(request, TrustTier.BLACKLISTED));
    }

    @Test
    void scoreIsNeverNegative() {
        MockHttpServletRequest request = request("GET", "/unmapped", 0);
        assertEquals(0, engine.calculateScore(request, TrustTier.HIGH));
    }

    @Test
    void writeToUnmappedPathAddsMethodRisk() {
        MockHttpServletRequest request = request("POST", "/unmapped", 0);
        assertEquals(10, engine.calculateScore(request, TrustTier.MEDIUM));
    }

    private MockHttpServletRequest request(String method, String uri, long contentLength) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.setContent(new byte[(int) contentLength]);
        return request;
    }
}
