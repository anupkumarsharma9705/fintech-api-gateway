package com.fintech.fintech_gateway.support;

import com.fintech.fintech_gateway.config.GatewayProperties;

import java.time.Duration;
import java.util.List;

/**
 * Shared deterministic configuration for unit tests. No Spring context, no
 * external services.
 */
public final class TestProperties {

    private TestProperties() {
    }

    public static GatewayProperties defaults() {
        GatewayProperties properties = new GatewayProperties();
        properties.getSecurity().setOpsApiKey("test-ops-key");
        properties.getSecurity().setMaxBodyBytes(4096);
        properties.getSecurity().setOpsMaxFailedAttempts(10);
        properties.getSecurity().setOpsLockoutWindow(Duration.ofMinutes(15));
        properties.getRateLimit().setWindow(Duration.ofMinutes(1));
        properties.getRateLimit().setHighTrustLimit(50);
        properties.getRateLimit().setMediumTrustLimit(30);
        properties.getRateLimit().setLowTrustLimit(10);
        properties.getRateLimit().setThrottleCost(3);
        properties.getRisk().setBlockThreshold(71);
        properties.getRisk().setThrottleThreshold(31);
        properties.getRisk().setZone("UTC");
        properties.getIdempotency().setTtl(Duration.ofHours(24));
        properties.getIdempotency().setMaxKeyLength(255);
        properties.getIdempotency().setRequiredPaths(List.of("/api/payments"));
        // Legacy pipeline tests exercise tenant headers directly. Auth-specific
        // tests opt back in and provide their own JwtDecoder.
        properties.getAuth().setEnabled(false);
        return properties;
    }
}
