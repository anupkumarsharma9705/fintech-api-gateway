package com.fintech.fintech_gateway.ratelimit;

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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RateLimiterTest {

    private StringRedisTemplate redisTemplate;
    private RateLimiter rateLimiter;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        Clock clock = Clock.fixed(Instant.parse("2026-01-15T10:00:00Z"), ZoneOffset.UTC);
        rateLimiter = new RateLimiter(redisTemplate, TestProperties.defaults(), clock);
    }

    @Test
    @SuppressWarnings("unchecked")
    void allowedRequestReturnsRemainingCapacity() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(List.of("1", "29", "0"));

        RateLimiter.Decision decision =
                rateLimiter.check("tenant", "1.2.3.4", TrustTier.MEDIUM, "req-1", 1);

        assertTrue(decision.allowed());
        assertEquals(30, decision.limit());
        assertEquals(29, decision.remaining());
        assertEquals(0, decision.retryAfterSeconds());
    }

    @Test
    @SuppressWarnings("unchecked")
    void deniedRequestReturnsRetryAfter() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(List.of("0", "0", "12"));

        RateLimiter.Decision decision =
                rateLimiter.check("tenant", "1.2.3.4", TrustTier.MEDIUM, "req-1", 1);

        assertFalse(decision.allowed());
        assertEquals(12, decision.retryAfterSeconds());
    }

    @Test
    @SuppressWarnings("unchecked")
    void numericRedisRepliesAreParsed() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(List.of(1L, 9L, 0L));

        RateLimiter.Decision decision =
                rateLimiter.check("tenant", "1.2.3.4", TrustTier.LOW, "req-1", 1);

        assertTrue(decision.allowed());
        assertEquals(10, decision.limit());
        assertEquals(9, decision.remaining());
    }

    @Test
    void redisFailureFailsClosed() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenThrow(new RedisConnectionFailureException("redis down"));

        GatewayRequestException exception = assertThrows(GatewayRequestException.class,
                () -> rateLimiter.check("tenant", "1.2.3.4", TrustTier.MEDIUM, "req-1", 1));

        assertEquals(503, exception.getStatus().value());
        assertEquals("rate_limiter_unavailable", exception.getCode());
    }

    @Test
    void malformedScriptResultFailsClosed() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(List.of("1"));

        assertThrows(GatewayRequestException.class,
                () -> rateLimiter.check("tenant", "1.2.3.4", TrustTier.MEDIUM, "req-1", 1));
    }

    @Test
    void disabledRateLimiterAllowsWithoutCallingRedis() {
        var properties = TestProperties.defaults();
        properties.getRateLimit().setEnabled(false);
        RateLimiter disabled = new RateLimiter(redisTemplate, properties,
                Clock.systemUTC());

        RateLimiter.Decision decision =
                disabled.check("tenant", "1.2.3.4", TrustTier.LOW, "req-1", 1);

        assertTrue(decision.allowed());
        verify(redisTemplate, never()).execute(any(RedisScript.class), anyList(), any(Object[].class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void quotaKeyIsTenantAndClientScoped() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(List.of(1L, 9L, 0L));

        rateLimiter.check("tenant-a", "client-9", TrustTier.MEDIUM, "req-1", 1);

        ArgumentCaptor<List<String>> keys = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(redisTemplate).execute(any(RedisScript.class), keys.capture(), args.capture());

        assertEquals(List.of(RedisKeys.quota("tenant-a", "client-9")), keys.getValue());
        assertEquals("60000", args.getValue()[1]);
        assertEquals("30", args.getValue()[2]);
        assertEquals("1", args.getValue()[3]);
        assertEquals(4, args.getValue().length, "GCRA single-bucket takes no member");
    }

    @Test
    @SuppressWarnings("unchecked")
    void sortedSetAlgorithmPassesTheRequestMember() {
        var properties = TestProperties.defaults();
        properties.getRateLimit().setAlgorithm("sorted-set");
        RateLimiter sortedSet = new RateLimiter(redisTemplate, properties,
                Clock.fixed(Instant.parse("2026-01-15T10:00:00Z"), ZoneOffset.UTC));
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(List.of(1L, 9L, 0L));

        sortedSet.check("tenant-a", "client-9", TrustTier.MEDIUM, "req-1", 3);

        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(redisTemplate).execute(any(RedisScript.class), anyList(), args.capture());
        assertEquals("req-1:" + Instant.parse("2026-01-15T10:00:00Z").toEpochMilli(),
                args.getValue()[3]);
        assertEquals("3", args.getValue()[4]);
        assertEquals(RateLimitAlgorithm.SORTED_SET, sortedSet.algorithm());
    }

    @Test
    void limitsAreTierSpecific() {
        assertEquals(50, rateLimiter.limitFor(TrustTier.HIGH));
        assertEquals(30, rateLimiter.limitFor(TrustTier.MEDIUM));
        assertEquals(10, rateLimiter.limitFor(TrustTier.LOW));
        assertEquals(0, rateLimiter.limitFor(TrustTier.BLACKLISTED));
    }
}
