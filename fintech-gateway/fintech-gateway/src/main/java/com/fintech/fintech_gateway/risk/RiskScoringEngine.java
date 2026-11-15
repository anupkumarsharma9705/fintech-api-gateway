package com.fintech.fintech_gateway.risk;

import com.fintech.fintech_gateway.config.GatewayProperties;
import com.fintech.fintech_gateway.trust.TrustTierService.TrustTier;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * Computes a deterministic 0-100 risk score from request properties and the
 * caller's trust tier. Frequency is deliberately not counted here: the
 * sliding-window rate limiter already does that atomically, and incrementing
 * a second Redis counter on every request doubled gateway latency for no
 * additional signal.
 */
@Component
public class RiskScoringEngine {

    private final Clock clock;
    private final ZoneId businessZone;

    public RiskScoringEngine(Clock clock, GatewayProperties properties) {
        this.clock = clock;
        this.businessZone = ZoneId.of(properties.getRisk().getZone());
    }

    public int calculateScore(HttpServletRequest request, TrustTier tier) {
        int score = 0;
        score += scoreEndpointSensitivity(request.getMethod(), request.getRequestURI());
        score += scorePayloadSize(request.getContentLengthLong());
        score += scoreTimeOfRequest();
        score += adjustmentFor(tier);
        return Math.min(Math.max(score, 0), 100);
    }

    private int scoreEndpointSensitivity(String method, String uri) {
        boolean write = isWrite(method);
        if (uri.startsWith("/api/payments")) {
            return write ? 30 : 15;
        }
        if (uri.contains("/balance")) {
            return 10;
        }
        if (uri.contains("/profile")) {
            return 5;
        }
        if (uri.startsWith("/api/")) {
            return write ? 15 : 5;
        }
        return write ? 10 : 0;
    }

    private int scorePayloadSize(long contentLength) {
        if (contentLength > 100_000) {
            return 20;
        }
        if (contentLength > 10_000) {
            return 10;
        }
        return 0;
    }

    private int scoreTimeOfRequest() {
        int hour = ZonedDateTime.now(clock.withZone(businessZone)).getHour();
        return (hour >= 23 || hour <= 5) ? 15 : 0;
    }

    private int adjustmentFor(TrustTier tier) {
        return switch (tier) {
            case HIGH -> -10;
            case MEDIUM -> 0;
            case LOW -> 20;
            case BLACKLISTED -> 100;
        };
    }

    private boolean isWrite(String method) {
        return !("GET".equalsIgnoreCase(method)
                || "HEAD".equalsIgnoreCase(method)
                || "OPTIONS".equalsIgnoreCase(method));
    }
}
