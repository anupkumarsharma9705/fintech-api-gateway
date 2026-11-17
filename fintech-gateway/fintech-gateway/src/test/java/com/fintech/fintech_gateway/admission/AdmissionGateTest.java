package com.fintech.fintech_gateway.admission;

import com.fintech.fintech_gateway.config.GatewayProperties;
import com.fintech.fintech_gateway.ratelimit.RateLimitAlgorithm;
import com.fintech.fintech_gateway.redis.RedisKeys;
import com.fintech.fintech_gateway.support.TestProperties;
import com.fintech.fintech_gateway.trust.TrustTierService.TrustTier;
import com.fintech.fintech_gateway.web.GatewayRequestException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdmissionGateTest {

    private static final String IP = "203.0.113.20";
    private static final String CLIENT = "client-9";
    private static final String TENANT = "tenant-a";
    private static final String REQUEST_ID = "req-12345678";
    private static final long NOW = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli();

    private StringRedisTemplate redisTemplate;
    private GatewayProperties properties;
    private AdmissionGate gate;

    @BeforeEach
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        properties = TestProperties.defaults();
        gate = new AdmissionGate(redisTemplate, properties,
                Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC));
    }

    @Test
    void allowedRequestReturnsTierLimitAndRemaining() {
        stubScript(List.of(1L, "MEDIUM", 30L, 29L, 0L, "OK"));

        AdmissionGate.Decision decision = gate.admit(TENANT, CLIENT, IP, REQUEST_ID, 1);

        assertTrue(decision.allowed());
        assertFalse(decision.blacklisted());
        assertEquals(TrustTier.MEDIUM, decision.tier());
        assertEquals(30, decision.limit());
        assertEquals(29, decision.remaining());
        assertEquals(0, decision.retryAfterSeconds());
        assertEquals(AdmissionGate.LimitReason.OK, decision.reason());
    }

    @Test
    void blacklistedCallerIsDeniedAndFlagged() {
        stubScript(List.of(0L, "BLACKLISTED", 0L, 0L, 0L, "BLACKLISTED"));

        AdmissionGate.Decision decision = gate.admit(TENANT, CLIENT, IP, REQUEST_ID, 1);

        assertFalse(decision.allowed());
        assertTrue(decision.blacklisted());
        assertEquals(TrustTier.BLACKLISTED, decision.tier());
        assertEquals(0, decision.limit());
        assertEquals(AdmissionGate.LimitReason.BLACKLISTED, decision.reason());
    }

    @Test
    void quotaLimitedCallerCarriesRetryAfterAndIsNotBlacklisted() {
        stubScript(List.of(0L, "MEDIUM", 30L, 0L, 12L, "QUOTA"));

        AdmissionGate.Decision decision = gate.admit(TENANT, CLIENT, IP, REQUEST_ID, 1);

        assertFalse(decision.allowed());
        assertFalse(decision.blacklisted());
        assertEquals(12, decision.retryAfterSeconds());
        assertEquals(0, decision.remaining());
        assertEquals(AdmissionGate.LimitReason.QUOTA, decision.reason());
    }

    @Test
    void abuseLimitedCallerIsDistinguishedFromQuotaExhaustion() {
        stubScript(List.of(0L, "MEDIUM", 6000L, 0L, 7L, "ABUSE"));

        AdmissionGate.Decision decision = gate.admit(TENANT, CLIENT, IP, REQUEST_ID, 1);

        assertFalse(decision.allowed());
        assertFalse(decision.blacklisted());
        assertEquals(AdmissionGate.LimitReason.ABUSE, decision.reason());
        assertEquals(7, decision.retryAfterSeconds());
    }

    @Test
    @SuppressWarnings("unchecked")
    void scriptReceivesTenantScopedKeysAndConfiguredTierLimits() {
        stubScript(List.of(1L, "MEDIUM", 30L, 29L, 0L, "OK"));

        gate.admit(TENANT, CLIENT, IP, REQUEST_ID, 1);

        ArgumentCaptor<List<String>> keys = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(redisTemplate).execute(any(RedisScript.class), keys.capture(), args.capture());

        assertEquals(List.of(
                RedisKeys.blacklist(TENANT, IP),
                RedisKeys.trust(TENANT, IP),
                RedisKeys.quota(TENANT, CLIENT),
                RedisKeys.abuse(TENANT, IP)), keys.getValue());

        Object[] argv = args.getValue();
        assertEquals(String.valueOf(NOW), argv[0]);
        assertEquals("60000", argv[1]);
        assertEquals("50", argv[2]);
        assertEquals("30", argv[3]);
        assertEquals("10", argv[4]);
        assertEquals("1", argv[5]);
        assertEquals("1", argv[6]);
        assertEquals("6000", argv[7]);
        assertEquals(8, argv.length, "GCRA admission takes no request member argument");
    }

    @Test
    @SuppressWarnings("unchecked")
    void sortedSetAlgorithmPassesTheRequestMember() {
        properties.getRateLimit().setAlgorithm("sorted-set");
        gate = new AdmissionGate(redisTemplate, properties,
                Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC));
        stubScript(List.of(1L, "MEDIUM", 30L, 29L, 0L, "OK"));

        gate.admit(TENANT, CLIENT, IP, REQUEST_ID, 1);

        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(redisTemplate).execute(any(RedisScript.class), anyList(), args.capture());
        Object[] argv = args.getValue();
        assertEquals(REQUEST_ID + ":" + NOW, argv[6]);
        assertEquals("1", argv[7]);
        assertEquals("6000", argv[8]);
        assertEquals(RateLimitAlgorithm.SORTED_SET, gate.algorithm());
    }

    @Test
    @SuppressWarnings("unchecked")
    void nonPositiveCostIsFlooredToOne() {
        stubScript(List.of(1L, "MEDIUM", 30L, 29L, 0L, "OK"));

        gate.admit(TENANT, CLIENT, IP, REQUEST_ID, 0);

        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(redisTemplate).execute(any(RedisScript.class), anyList(), args.capture());
        assertEquals("1", args.getValue()[6]);
    }

    @Test
    void disabledRateLimitingStillRejectsBlacklistedCallers() {
        properties.getRateLimit().setEnabled(false);
        stubScript(List.of(0L, "BLACKLISTED", 0L, 0L, 0L, "BLACKLISTED"));

        AdmissionGate.Decision decision = gate.admit(TENANT, CLIENT, IP, REQUEST_ID, 1);

        assertFalse(decision.allowed());
        assertTrue(decision.blacklisted());
    }

    @Test
    void unknownTierStringFallsBackToMedium() {
        stubScript(List.of(1L, "SUPERUSER", 30L, 29L, 0L, "OK"));

        assertEquals(TrustTier.MEDIUM,
                gate.admit(TENANT, CLIENT, IP, REQUEST_ID, 1).tier());
    }

    @Test
    void nullTierFallsBackToMedium() {
        stubScript(Arrays.asList(1L, null, 30L, 29L, 0L, "OK"));

        assertEquals(TrustTier.MEDIUM,
                gate.admit(TENANT, CLIENT, IP, REQUEST_ID, 1).tier());
    }

    @Test
    void unknownReasonFallsBackToOk() {
        stubScript(List.of(1L, "MEDIUM", 30L, 29L, 0L, "SOMETHING_NEW"));

        assertEquals(AdmissionGate.LimitReason.OK,
                gate.admit(TENANT, CLIENT, IP, REQUEST_ID, 1).reason());
    }

    @Test
    void truncatedScriptResultFailsClosed() {
        stubScript(List.of(1L, "MEDIUM"));

        GatewayRequestException exception = assertThrows(GatewayRequestException.class,
                () -> gate.admit(TENANT, CLIENT, IP, REQUEST_ID, 1));

        assertEquals(503, exception.getStatus().value());
        assertEquals("rate_limiter_unavailable", exception.getCode());
    }

    @Test
    void nullScriptResultFailsClosed() {
        stubScript(null);

        GatewayRequestException exception = assertThrows(GatewayRequestException.class,
                () -> gate.admit(TENANT, CLIENT, IP, REQUEST_ID, 1));

        assertEquals(503, exception.getStatus().value());
        assertEquals("rate_limiter_unavailable", exception.getCode());
    }

    @Test
    @SuppressWarnings("unchecked")
    void redisFailureFailsClosed() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenThrow(new RedisConnectionFailureException("down"));

        GatewayRequestException exception = assertThrows(GatewayRequestException.class,
                () -> gate.admit(TENANT, CLIENT, IP, REQUEST_ID, 1));

        assertEquals(503, exception.getStatus().value());
        assertEquals("rate_limiter_unavailable", exception.getCode());
    }

    @Test
    @SuppressWarnings("unchecked")
    void unexpectedScriptFailureFailsClosed() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenThrow(new IllegalStateException("boom"));

        GatewayRequestException exception = assertThrows(GatewayRequestException.class,
                () -> gate.admit(TENANT, CLIENT, IP, REQUEST_ID, 1));

        assertEquals(503, exception.getStatus().value());
        assertEquals("rate_limiter_unavailable", exception.getCode());
    }

    @Test
    void defaultAlgorithmIsGcra() {
        assertEquals(RateLimitAlgorithm.GCRA, gate.algorithm());
    }

    @Test
    void limitForMatchesConfiguredTiers() {
        assertEquals(50, gate.limitFor(TrustTier.HIGH));
        assertEquals(30, gate.limitFor(TrustTier.MEDIUM));
        assertEquals(10, gate.limitFor(TrustTier.LOW));
        assertEquals(0, gate.limitFor(TrustTier.BLACKLISTED));
    }

    @SuppressWarnings("unchecked")
    private void stubScript(Object result) {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(result);
    }
}
