package com.fintech.fintech_gateway.risk;

import com.fintech.fintech_gateway.config.GatewayProperties;
import org.springframework.stereotype.Component;

/**
 * Maps a risk score to an allow/throttle/block decision. Thresholds are
 * validated at construction so a misconfigured environment fails at startup
 * instead of silently blocking every request.
 */
@Component
public class RiskDecisionEngine {

    private final int blockThreshold;
    private final int throttleThreshold;

    public RiskDecisionEngine(GatewayProperties properties) {
        this.blockThreshold = properties.getRisk().getBlockThreshold();
        this.throttleThreshold = properties.getRisk().getThrottleThreshold();
        if (throttleThreshold <= 0) {
            throw new IllegalArgumentException("gateway.risk.throttle-threshold must be positive");
        }
        if (blockThreshold <= throttleThreshold) {
            throw new IllegalArgumentException(
                    "gateway.risk.block-threshold must be greater than throttle-threshold");
        }
    }

    public Decision decide(int riskScore) {
        if (riskScore >= blockThreshold) {
            return Decision.BLOCK;
        }
        if (riskScore >= throttleThreshold) {
            return Decision.THROTTLE;
        }
        return Decision.ALLOW;
    }

    public int getBlockThreshold() {
        return blockThreshold;
    }

    public int getThrottleThreshold() {
        return throttleThreshold;
    }

    public enum Decision {
        ALLOW, THROTTLE, BLOCK
    }
}
