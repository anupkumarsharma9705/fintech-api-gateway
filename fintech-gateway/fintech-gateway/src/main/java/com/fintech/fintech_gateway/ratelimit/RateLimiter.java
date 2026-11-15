package com.fintech.fintech_gateway.ratelimit;

import com.fintech.fintech_gateway.config.GatewayProperties;
import com.fintech.fintech_gateway.trust.TrustTierService.TrustTier;
import com.fintech.fintech_gateway.web.GatewayRequestException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

/**
 * Redis-backed sliding-window rate limiter.
 *
 * <p>A sorted set holds one member per consumed slot. Expired members are
 * removed inside the Lua script, so the window is exact rather than the
 * fixed-window approximation used by INCR+EXPIRE (which allows a 2x burst
 * across the window boundary).</p>
 *
 * <p>The script runs atomically on Redis, so multiple gateway instances
 * share one consistent limit. If Redis is unavailable the limiter fails
 * closed: silently allowing unlimited traffic would turn a cache outage
 * into an unbounded abuse window.</p>
 */
@Component
public class RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);

    public record Decision(boolean allowed, int limit, int remaining, long retryAfterSeconds) {
    }

    private static final DefaultRedisScript<List> SLIDING_WINDOW_SCRIPT = new DefaultRedisScript<>("""
            local key = KEYS[1]
            local now = tonumber(ARGV[1])
            local window = tonumber(ARGV[2])
            local limit = tonumber(ARGV[3])
            local member = ARGV[4]
            local cost = tonumber(ARGV[5])

            redis.call('ZREMRANGEBYSCORE', key, '-inf', now - window)
            local count = redis.call('ZCARD', key)

            if count + cost > limit then
              local oldest = redis.call('ZRANGE', key, 0, 0, 'WITHSCORES')
              local retry = 1
              if oldest[2] ~= nil then
                retry = math.max(1, math.ceil((tonumber(oldest[2]) + window - now) / 1000))
              end
              return {0, math.max(0, limit - count), retry}
            end

            for i = 1, cost do
              redis.call('ZADD', key, now, member .. ':' .. i)
            end
            redis.call('PEXPIRE', key, window)
            return {1, limit - count - cost, 0}
            """, List.class);

    private final StringRedisTemplate redisTemplate;
    private final GatewayProperties properties;
    private final Clock clock;

    public RateLimiter(StringRedisTemplate redisTemplate,
                       GatewayProperties properties,
                       Clock clock) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
        this.clock = clock;
    }

    public Decision check(String tenantId, String clientIp, TrustTier tier,
                          String requestId, int cost) {
        int limit = limitFor(tier);
        if (!properties.getRateLimit().isEnabled()) {
            return new Decision(true, Integer.MAX_VALUE, Integer.MAX_VALUE, 0);
        }
        if (limit <= 0) {
            return new Decision(false, 0, 0, windowSeconds());
        }

        long now = clock.millis();
        Duration window = properties.getRateLimit().getWindow();
        String key = "ratelimit:" + tenantId + ":" + clientIp;
        String member = requestId + ":" + now;

        try {
            List<?> result = redisTemplate.execute(SLIDING_WINDOW_SCRIPT,
                    List.of(key),
                    String.valueOf(now),
                    String.valueOf(window.toMillis()),
                    String.valueOf(limit),
                    member,
                    String.valueOf(Math.max(1, cost)));

            if (result == null || result.size() < 3) {
                throw new IllegalStateException("Rate limiter script returned an unexpected result");
            }
            boolean allowed = asLong(result.get(0)) == 1L;
            int remaining = (int) Math.max(0, asLong(result.get(1)));
            long retryAfter = Math.max(0, asLong(result.get(2)));
            return new Decision(allowed, limit, remaining, retryAfter);
        } catch (DataAccessException e) {
            log.error("Rate limiter dependency unavailable", e);
            throw new GatewayRequestException(HttpStatus.SERVICE_UNAVAILABLE,
                    "rate_limiter_unavailable",
                    "Request admission is temporarily unavailable. Retry shortly.");
        } catch (RuntimeException e) {
            log.error("Rate limiter failed unexpectedly", e);
            throw new GatewayRequestException(HttpStatus.SERVICE_UNAVAILABLE,
                    "rate_limiter_unavailable",
                    "Request admission is temporarily unavailable. Retry shortly.");
        }
    }

    public int limitFor(TrustTier tier) {
        return switch (tier) {
            case HIGH -> properties.getRateLimit().getHighTrustLimit();
            case MEDIUM -> properties.getRateLimit().getMediumTrustLimit();
            case LOW -> properties.getRateLimit().getLowTrustLimit();
            case BLACKLISTED -> 0;
        };
    }

    private long windowSeconds() {
        return Math.max(1, properties.getRateLimit().getWindow().toSeconds());
    }

    private long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(String.valueOf(value));
    }
}
