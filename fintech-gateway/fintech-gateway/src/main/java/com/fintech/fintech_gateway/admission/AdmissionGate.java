package com.fintech.fintech_gateway.admission;

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
 * Single round trip for everything the admission decision needs.
 *
 * <p>Previously the request path made five sequential Redis calls: a blacklist
 * {@code EXISTS}, a trust-tier {@code GET} on cache miss, the rate-limit
 * {@code EVALSHA}, a good-behaviour {@code INCR} and an unconditional
 * throttle-counter {@code DEL}. Those calls were serialized, so each one added
 * a full round trip to the request latency and multiplied Redis load by five.</p>
 *
 * <p>This class collapses the three <em>pre-decision</em> reads into one Lua
 * script: blacklist lookup, tier resolution and the sliding-window check run
 * atomically on the Redis side and return a single result. The post-decision
 * writes (good-behaviour counter, throttle reset) stay best-effort and are
 * dispatched asynchronously by {@code TrustTierService#recordSuccess}.</p>
 *
 * <p>Failure semantics are unchanged and deliberately fail-closed: if Redis
 * cannot answer, the request is rejected rather than admitted unchecked. The
 * error code {@code rate_limiter_unavailable} is retained for client contract
 * stability even though this check now also covers the trust store.</p>
 */
@Component
public class AdmissionGate {

    private static final Logger log = LoggerFactory.getLogger(AdmissionGate.class);

    /**
     * Returns {@code {allowed, tier, limit, remaining, retryAfter}}.
     *
     * <p>KEYS: blacklist, trust, rate-limit bucket.
     * ARGV: now(ms), window(ms), highLimit, mediumLimit, lowLimit, enabled,
     * member, cost.</p>
     */
    private static final DefaultRedisScript<List> ADMIT_SCRIPT = new DefaultRedisScript<>("""
            local blacklistKey = KEYS[1]
            local trustKey = KEYS[2]
            local bucketKey = KEYS[3]

            local now = tonumber(ARGV[1])
            local window = tonumber(ARGV[2])
            local limitHigh = tonumber(ARGV[3])
            local limitMedium = tonumber(ARGV[4])
            local limitLow = tonumber(ARGV[5])
            local enabled = tonumber(ARGV[6])
            local member = ARGV[7]
            local cost = tonumber(ARGV[8])

            if redis.call('EXISTS', blacklistKey) == 1 then
              return {0, 'BLACKLISTED', 0, 0, 0}
            end

            local tier = redis.call('GET', trustKey)
            if tier == false then
              tier = 'MEDIUM'
            end

            local limit
            if tier == 'HIGH' then
              limit = limitHigh
            elseif tier == 'LOW' then
              limit = limitLow
            elseif tier == 'BLACKLISTED' then
              limit = 0
            else
              tier = 'MEDIUM'
              limit = limitMedium
            end

            if enabled == 0 then
              return {1, tier, limit, limit, 0}
            end

            if limit <= 0 then
              return {0, tier, 0, 0, 60}
            end

            redis.call('ZREMRANGEBYSCORE', bucketKey, '-inf', now - window)
            local count = redis.call('ZCARD', bucketKey)

            if count + cost > limit then
              local retry = 1
              local oldest = redis.call('ZRANGE', bucketKey, 0, 0, 'WITHSCORES')
              if oldest[2] ~= nil then
                retry = math.max(1, math.ceil((tonumber(oldest[2]) + window - now) / 1000))
              end
              return {0, tier, limit, math.max(0, limit - count), retry}
            end

            for i = 1, cost do
              redis.call('ZADD', bucketKey, now, member .. ':' .. i)
            end
            redis.call('PEXPIRE', bucketKey, window)
            return {1, tier, limit, limit - count - cost, 0}
            """, List.class);

    private final StringRedisTemplate redisTemplate;
    private final GatewayProperties properties;
    private final Clock clock;

    public AdmissionGate(StringRedisTemplate redisTemplate,
                         GatewayProperties properties,
                         Clock clock) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
        this.clock = clock;
    }

    public record Decision(boolean allowed, TrustTier tier, boolean blacklisted,
                           int limit, int remaining, long retryAfterSeconds) {
    }

    public Decision admit(String tenantId, String clientIp, String requestId, int cost) {
        GatewayProperties.RateLimit rateLimit = properties.getRateLimit();
        long now = clock.millis();
        Duration window = rateLimit.getWindow();
        int effectiveCost = Math.max(1, cost);

        try {
            List<?> result = redisTemplate.execute(ADMIT_SCRIPT,
                    List.of("blacklist:" + clientIp,
                            "trust:" + clientIp,
                            "ratelimit:" + tenantId + ":" + clientIp),
                    String.valueOf(now),
                    String.valueOf(window.toMillis()),
                    String.valueOf(rateLimit.getHighTrustLimit()),
                    String.valueOf(rateLimit.getMediumTrustLimit()),
                    String.valueOf(rateLimit.getLowTrustLimit()),
                    rateLimit.isEnabled() ? "1" : "0",
                    requestId + ":" + now,
                    String.valueOf(effectiveCost));

            if (result == null || result.size() < 5) {
                throw new IllegalStateException("Admission script returned an unexpected result");
            }

            boolean allowed = asLong(result.get(0)) == 1L;
            TrustTier tier = parseTier(result.get(1));
            int limit = (int) Math.max(0, asLong(result.get(2)));
            int remaining = (int) Math.max(0, asLong(result.get(3)));
            long retryAfter = Math.max(0, asLong(result.get(4)));

            return new Decision(allowed, tier, tier == TrustTier.BLACKLISTED,
                    limit, remaining, retryAfter);
        } catch (DataAccessException e) {
            log.error("Admission dependency unavailable", e);
            throw unavailable();
        } catch (RuntimeException e) {
            log.error("Admission check failed unexpectedly", e);
            throw unavailable();
        }
    }

    public int limitFor(TrustTier tier) {
        GatewayProperties.RateLimit rateLimit = properties.getRateLimit();
        return switch (tier) {
            case HIGH -> rateLimit.getHighTrustLimit();
            case MEDIUM -> rateLimit.getMediumTrustLimit();
            case LOW -> rateLimit.getLowTrustLimit();
            case BLACKLISTED -> 0;
        };
    }

    private GatewayRequestException unavailable() {
        return new GatewayRequestException(HttpStatus.SERVICE_UNAVAILABLE,
                "rate_limiter_unavailable",
                "Request admission is temporarily unavailable. Retry shortly.");
    }

    private TrustTier parseTier(Object value) {
        if (value == null) {
            return TrustTier.MEDIUM;
        }
        try {
            return TrustTier.valueOf(String.valueOf(value));
        } catch (IllegalArgumentException unknown) {
            log.warn("Ignoring unknown trust tier value '{}'", value);
            return TrustTier.MEDIUM;
        }
    }

    private long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(String.valueOf(value));
    }
}
