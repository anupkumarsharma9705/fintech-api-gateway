package com.fintech.fintech_gateway.ratelimit;

import com.fintech.fintech_gateway.config.GatewayProperties;
import com.fintech.fintech_gateway.redis.RedisKeys;
import com.fintech.fintech_gateway.trust.TrustTierService.TrustTier;
import com.fintech.fintech_gateway.web.GatewayRequestException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

/**
 * Redis-backed rate limiter for the rare THROTTLE penalty path.
 *
 * <p>The normal admission check already consumed one slot from the
 * tenant + client bucket. A risk-throttled request consumes the configured
 * extra cost from the same bucket, so the penalty is visible to the next
 * admission check.</p>
 *
 * <p>The stored algorithm is selected by
 * {@code gateway.rate-limit.algorithm}. GCRA uses one number per bucket;
 * the sorted-set fallback keeps the exact per-event window available.</p>
 *
 * <p>If Redis is unavailable the limiter fails closed: silently allowing
 * unlimited traffic would turn a cache outage into an unbounded abuse
 * window.</p>
 */
@Component
public class RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);

    public record Decision(boolean allowed, int limit, int remaining, long retryAfterSeconds) {
    }

    private final StringRedisTemplate redisTemplate;
    private final GatewayProperties properties;
    private final Clock clock;
    private final RateLimitAlgorithm algorithm;

    public RateLimiter(StringRedisTemplate redisTemplate,
                       GatewayProperties properties,
                       Clock clock) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
        this.clock = clock;
        this.algorithm = RateLimitAlgorithm.from(properties.getRateLimit().getAlgorithm());
    }

    public Decision check(String tenantId, String clientId, TrustTier tier,
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
        String key = RedisKeys.quota(tenantId, clientId);
        int effectiveCost = Math.max(1, cost);

        try {
            List<?> result = algorithm == RateLimitAlgorithm.GCRA
                    ? redisTemplate.execute(RateLimitScripts.GCRA_SINGLE,
                            List.of(key),
                            String.valueOf(now),
                            String.valueOf(window.toMillis()),
                            String.valueOf(limit),
                            String.valueOf(effectiveCost))
                    : redisTemplate.execute(RateLimitScripts.SORTED_SET_SINGLE,
                            List.of(key),
                            String.valueOf(now),
                            String.valueOf(window.toMillis()),
                            String.valueOf(limit),
                            requestId + ":" + now,
                            String.valueOf(effectiveCost));

            if (result == null || result.size() < 3) {
                throw new IllegalStateException("Rate limiter script returned an unexpected result");
            }
            boolean allowed = asLong(result.get(0)) == 1L;
            int remaining = (int) Math.max(0, asLong(result.get(1)));
            long retryAfter = Math.max(0, asLong(result.get(2)));
            return new Decision(allowed, limit, remaining, retryAfter);
        } catch (DataAccessException e) {
            log.error("Rate limiter dependency unavailable", e);
            throw unavailable();
        } catch (RuntimeException e) {
            log.error("Rate limiter failed unexpectedly", e);
            throw unavailable();
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

    public RateLimitAlgorithm algorithm() {
        return algorithm;
    }

    private GatewayRequestException unavailable() {
        return new GatewayRequestException(HttpStatus.SERVICE_UNAVAILABLE,
                "rate_limiter_unavailable",
                "Request admission is temporarily unavailable. Retry shortly.");
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
