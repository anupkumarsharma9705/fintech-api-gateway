package com.fintech.fintech_gateway.trust;

import com.fintech.fintech_gateway.persistence.PersistenceService;
import com.fintech.fintech_gateway.redis.RedisKeys;
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

    private static final String TENANT = "tenant-a";
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
    @SuppressWarnings("unchecked")
    void firstBlockDowngradesToLowWithShortCooldown() {
        when(valueOperations.get(RedisKeys.trust(TENANT, IP))).thenReturn(null);
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(1L);

        service.recordBlock(TENANT, IP);

        verify(valueOperations).set(RedisKeys.trust(TENANT, IP), "LOW",
                Duration.ofMinutes(1));
        verify(persistenceService).saveTrustHistory(eq(TENANT), eq(IP), eq("MEDIUM"),
                eq("LOW"), contains("1"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void fourthBlockPermanentlyBlacklists() {
        when(valueOperations.get(RedisKeys.trust(TENANT, IP))).thenReturn(null);
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(4L);

        service.recordBlock(TENANT, IP);

        verify(valueOperations).set(RedisKeys.blacklist(TENANT, IP), "PERMANENT");
        verify(persistenceService).saveTrustHistory(eq(TENANT), eq(IP), eq("MEDIUM"),
                eq("BLACKLISTED"), contains("4"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void blockRecordingFailureIsBestEffort() {
        when(valueOperations.get(RedisKeys.trust(TENANT, IP))).thenReturn(null);
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenThrow(new RedisConnectionFailureException("down"));

        service.recordBlock(TENANT, IP);

        verify(persistenceService, never()).saveTrustHistory(anyString(), anyString(),
                anyString(), anyString(), anyString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void repeatedThrottlesDowngradeTrust() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(6L);

        int count = service.recordThrottle(TENANT, IP);

        assertEquals(6, count);
        verify(valueOperations).set(RedisKeys.trust(TENANT, IP), "LOW",
                Duration.ofMinutes(30));
        verify(persistenceService).saveTrustHistory(eq(TENANT), eq(IP), eq("UNKNOWN"),
                eq("LOW"), contains("6"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void throttleRecordingFailureReturnsZero() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenThrow(new RedisConnectionFailureException("down"));

        assertEquals(0, service.recordThrottle(TENANT, IP));
    }

    @Test
    @SuppressWarnings("unchecked")
    void sustainedGoodBehaviourUpgradesToHigh() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(100L);

        service.recordSuccess(TENANT, IP, TrustTier.MEDIUM);

        verify(valueOperations).set(RedisKeys.trust(TENANT, IP), "HIGH",
                Duration.ofDays(7));
        verify(redisTemplate).delete(RedisKeys.goodCount(TENANT, IP));
        verify(persistenceService).saveTrustHistory(TENANT, IP, "MEDIUM", "HIGH",
                "Sustained clean traffic");
    }

    @Test
    @SuppressWarnings("unchecked")
    void cleanTrafficBelowThresholdDoesNotUpgrade() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(99L);

        service.recordSuccess(TENANT, IP, TrustTier.MEDIUM);

        verify(valueOperations, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void cleanTrafficFromNonMediumTierDoesNotCount() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(0L);

        service.recordSuccess(TENANT, IP, TrustTier.HIGH);

        verify(persistenceService, never()).saveTrustHistory(anyString(), anyString(),
                anyString(), anyString(), anyString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void successRecordingFailureIsBestEffort() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenThrow(new RedisConnectionFailureException("down"));

        service.recordSuccess(TENANT, IP, TrustTier.MEDIUM);

        verify(persistenceService, never()).saveTrustHistory(anyString(), anyString(),
                anyString(), anyString(), anyString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void operatorResetClearsAllPenalties() {
        when(valueOperations.get(RedisKeys.trust(TENANT, IP))).thenReturn("LOW");

        service.resetToMedium(TENANT, IP, "confirmed false positive");

        verify(redisTemplate).delete(List.of(
                RedisKeys.blacklist(TENANT, IP),
                RedisKeys.blockCount(TENANT, IP),
                RedisKeys.throttleCount(TENANT, IP),
                RedisKeys.goodCount(TENANT, IP),
                RedisKeys.trust(TENANT, IP)));
        verify(persistenceService).saveTrustHistory(TENANT, IP, "LOW", "MEDIUM",
                "confirmed false positive");
    }

    @Test
    @SuppressWarnings("unchecked")
    void operatorResetFailsLoudlyWhenRedisIsDown() {
        when(valueOperations.get(RedisKeys.trust(TENANT, IP))).thenReturn("LOW");
        when(redisTemplate.delete(anyList()))
                .thenThrow(new RedisConnectionFailureException("down"));

        GatewayRequestException exception = assertThrows(GatewayRequestException.class,
                () -> service.resetToMedium(TENANT, IP, "reason"));

        assertEquals(503, exception.getStatus().value());
        assertEquals("trust_store_unavailable", exception.getCode());
    }

    @Test
    void tenantScopingSeparatesTheSameIpAcrossTenants() {
        org.junit.jupiter.api.Assertions.assertNotEquals(
                RedisKeys.trust("tenant-a", IP),
                RedisKeys.trust("tenant-b", IP));
    }

    @Test
    void riskAdjustmentsMatchTier() {
        assertEquals(-10, service.getRiskAdjustment(TrustTier.HIGH));
        assertEquals(0, service.getRiskAdjustment(TrustTier.MEDIUM));
        assertEquals(20, service.getRiskAdjustment(TrustTier.LOW));
        assertEquals(100, service.getRiskAdjustment(TrustTier.BLACKLISTED));
    }
}
