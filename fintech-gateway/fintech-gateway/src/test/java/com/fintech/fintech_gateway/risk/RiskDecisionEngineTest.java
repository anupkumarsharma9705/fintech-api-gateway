package com.fintech.fintech_gateway.risk;

import com.fintech.fintech_gateway.config.GatewayProperties;
import com.fintech.fintech_gateway.support.TestProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RiskDecisionEngineTest {

    private RiskDecisionEngine engine;

    @BeforeEach
    void setUp() {
        GatewayProperties properties = TestProperties.defaults();
        properties.getRisk().setThrottleThreshold(31);
        properties.getRisk().setBlockThreshold(71);
        engine = new RiskDecisionEngine(properties);
    }

    @Test
    void scoreBelowThrottleThresholdAllows() {
        assertEquals(RiskDecisionEngine.Decision.ALLOW, engine.decide(30));
    }

    @Test
    void scoreAtThrottleThresholdThrottles() {
        assertEquals(RiskDecisionEngine.Decision.THROTTLE, engine.decide(31));
    }

    @Test
    void scoreBelowBlockThresholdThrottles() {
        assertEquals(RiskDecisionEngine.Decision.THROTTLE, engine.decide(70));
    }

    @Test
    void scoreAtBlockThresholdBlocks() {
        assertEquals(RiskDecisionEngine.Decision.BLOCK, engine.decide(71));
    }

    @Test
    void scoreAtMaximumBlocks() {
        assertEquals(RiskDecisionEngine.Decision.BLOCK, engine.decide(100));
    }

    @Test
    void invalidThresholdOrderFailsAtStartup() {
        GatewayProperties properties = TestProperties.defaults();
        properties.getRisk().setThrottleThreshold(80);
        properties.getRisk().setBlockThreshold(70);
        assertThrows(IllegalArgumentException.class, () -> new RiskDecisionEngine(properties));
    }

    @Test
    void nonPositiveThrottleThresholdFailsAtStartup() {
        GatewayProperties properties = TestProperties.defaults();
        properties.getRisk().setThrottleThreshold(0);
        assertThrows(IllegalArgumentException.class, () -> new RiskDecisionEngine(properties));
    }
}
