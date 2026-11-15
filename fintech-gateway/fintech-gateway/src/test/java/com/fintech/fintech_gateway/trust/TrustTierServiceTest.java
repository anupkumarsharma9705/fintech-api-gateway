package com.fintech.fintech_gateway.trust;

import com.fintech.fintech_gateway.persistence.PersistenceService;
import com.fintech.fintech_gateway.trust.TrustTierService.TrustTier;
import com.fintech.fintech_gateway.web.GatewayRequestException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TrustTierServiceTest {

    private static final String IP = "203.0.113.10";

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOperations;
    private PersistenceService persistenceService;
    private TrustTierService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        persistenceService = mock(PersistenceService.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        service = new TrustTierService(redisTemplate, persistenceService);
    }

    @Test
    void unknownIpDefaultsToMedium() {
        when(redisTemplate.hasKey("blacklist:" + IP)).thenReturn(false);
        when(valueOperations.get("trust:" + IP)).thenReturn(null);

        assertEquals(TrustTier.MEDIUM, service.getTrustTier(IP));
    }

    @Test
    void storedTierIsReturned() {
        when(redisTemplate.hasKey("blacklist:" + IP)).thenReturn(false);
        when(valueOperations.get("trust:" + IP)).thenReturn("HIGH");

        assertEquals(TrustTier.HIGH, service.getTrustTier(IP));
    }

    @Test
    void blacklistedIpShortCircuitsTierLookup() {
        when(redisTemplate.hasKey("blacklist:" + IP)).thenReturn(true);

        assertEquals(TrustTier.BLACKLISTED, service.getTrustTier(IP));
        verify(valueOperations, never()).get("trust:" + IP);
    }

    @Test
    void redisReadFailureFailsClosed() {
        when(redisTemplate.hasKey("blacklist:" + IP))
                .thenThrow(new RedisConnectionFailureException("down"));

        GatewayRequestException exception = assertThrows(GatewayRequestException.class,
                () -> service.getTrustTier(IP));

        assertEquals(503, exception.getStatus().value());
        assertEquals("trust_store_unavailable", exception.getCode());
    }

    @Test
    @SuppressWarnings("unchecked")
    void firstBlockDowngradesToLowWithShortCooldown() {
        when(valueOperations.get("trust:" + IP)).thenReturn(null);
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(1L);

        service.recordBlock(IP);

        verify(valueOperations).set("trust:" + IP, "LOW", Duration.ofMinutes(1));
        verify(persistenceService).saveTrustHistory(eq(IP), eq("MEDIUM"), eq("LOW"), contains("1"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void fourthBlockPermanentlyBlacklists() {
        when(valueOperations.get("trust:" + IP)).thenReturn(null);
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(4L);

        service.recordBlock(IP);

        verify(valueOperations).set("blacklist:" + IP, "PERMANENT");
        verify(persistenceService).saveTrustHistory(eq(IP), eq("MEDIUM"),
                eq("BLACKLISTED"), contains("4"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void blockRecordingFailureIsBestEffort() {
        when(valueOperations.get("trust:" + IP)).thenReturn(null);
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenThrow(new RedisConnectionFailureException("down"));

        service.recordBlock(IP);

        verify(persistenceService, never()).saveTrustHistory(anyString(), anyString(),
                anyString(), anyString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void repeatedThrottlesDowngradeTrust() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(6L);

        int count = service.recordThrottle(IP);

        assertEquals(6, count);
        verify(valueOperations).set("trust:" + IP, "LOW", Duration.ofMinutes(30));
        verify(persistenceService).saveTrustHistory(eq(IP), eq("UNKNOWN"), eq("LOW"),
                contains("6"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void throttleRecordingFailureReturnsZero() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenThrow(new RedisConnectionFailureException("down"));

        assertEquals(0, service.recordThrottle(IP));
    }

    @Test
    @SuppressWarnings("unchecked")
    void sustainedGoodBehaviourUpgradesToHigh() {
        when(redisTemplate.hasKey("blacklist:" + IP)).thenReturn(false);
        when(valueOperations.get("trust:" + IP)).thenReturn(null);
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(100L);

        service.recordGoodBehaviour(IP);

        verify(valueOperations).set("trust:" + IP, "HIGH", Duration.ofDays(7));
        verify(redisTemplate).delete("goodcount:" + IP);
        verify(persistenceService).saveTrustHistory(IP, "MEDIUM", "HIGH",
                "Sustained clean traffic");
    }

    @Test
    @SuppressWarnings("unchecked")
    void operatorResetClearsAllPenalties() {
        when(valueOperations.get("trust:" + IP)).thenReturn("LOW");

        service.resetToMedium(IP, "confirmed false positive");

        verify(redisTemplate).delete(List.of(
                "blacklist:" + IP,
                "blockcount:" + IP,
                "throttlecount:" + IP,
                "goodcount:" + IP,
                "trust:" + IP));
        verify(persistenceService).saveTrustHistory(IP, "LOW", "MEDIUM",
                "confirmed false positive");
    }

    @Test
    @SuppressWarnings("unchecked")
    void operatorResetFailsLoudlyWhenRedisIsDown() {
        when(valueOperations.get("trust:" + IP)).thenReturn("LOW");
        when(redisTemplate.delete(anyList()))
                .thenThrow(new RedisConnectionFailureException("down"));

        GatewayRequestException exception = assertThrows(GatewayRequestException.class,
                () -> service.resetToMedium(IP, "reason"));

        assertEquals(503, exception.getStatus().value());
    }

    @Test
    void riskAdjustmentsMatchTier() {
        assertEquals(-10, service.getRiskAdjustment(TrustTier.HIGH));
        assertEquals(0, service.getRiskAdjustment(TrustTier.MEDIUM));
        assertEquals(20, service.getRiskAdjustment(TrustTier.LOW));
        assertEquals(100, service.getRiskAdjustment(TrustTier.BLACKLISTED));
    }
}
