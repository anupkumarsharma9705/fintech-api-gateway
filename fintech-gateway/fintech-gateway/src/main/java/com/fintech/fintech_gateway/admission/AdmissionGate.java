package com.fintech.fintech_gateway.admission;

import com.fintech.fintech_gateway.config.GatewayProperties;
import com.fintech.fintech_gateway.ratelimit.RateLimitAlgorithm;
import com.fintech.fintech_gateway.ratelimit.RateLimitScripts;
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
 * Single round trip for everything the admission decision needs.
 *
 * <p>The script resolves the blacklist, the trust tier, the tenant+client
 * quota and the tenant+IP abuse ceiling atomically. Previously these were
 * separate serialized calls, so every request paid several network round
 * trips and Redis load multiplied by the call count.</p>
 *
 * <p>All keys for one tenant share a Redis Cluster hash tag. The quota is
 * keyed on tenant + authenticated client (IP is not a quota dimension), while
 * the abuse ceiling is keyed on tenant + IP. Behind a NAT or load balancer
 * this stops thousands of clients from sharing one quota bucket, without
 * letting an abusive host create unlimited buckets by rotating clients.</p>
 *
 * <p>Failure semantics stay fail-closed: if Redis cannot answer, the request
 * is rejected rather than admitted unchecked. The error code
 * {@code rate_limiter_unavailable} is retained for client contract stability
 * even though this check now also covers trust and abuse state.</p>
 */
@Component
public class AdmissionGate {

    private static final Logger log = LoggerFactory.getLogger(AdmissionGate.class);

    public enum LimitReason {
        OK, BLACKLISTED, QUOTA, ABUSE
    }

    public record Decision(boolean allowed, TrustTier tier, int limit, int remaining,
                           long retryAfterSeconds, LimitReason reason) {

        public boolean blacklisted() {
            return reason == LimitReason.BLACKLISTED || tier == TrustTier.BLACKLISTED;
        }
    }

    private final StringRedisTemplate redisTemplate;
    private final GatewayProperties properties;
    private final Clock clock;
    private final RateLimitAlgorithm algorithm;

    public AdmissionGate(StringRedisTemplate redisTemplate,
                         GatewayProperties properties,
                         Clock clock) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
        this.clock = clock;
        this.algorithm = RateLimitAlgorithm.from(properties.getRateLimit().getAlgorithm());
    }

    public Decision admit(String tenantId, String clientId, String clientIp,
                          String requestId, int cost) {
        GatewayProperties.RateLimit rateLimit = properties.getRateLimit();
        long now = clock.millis();
        Duration window = rateLimit.getWindow();
        int effectiveCost = Math.max(1, cost);
        int abuseLimit = rateLimit.getAbuseLimitPerIp();

        List<String> keys = List.of(
                RedisKeys.blacklist(tenantId, clientIp),
                RedisKeys.trust(tenantId, clientIp),
                RedisKeys.quota(tenantId, clientId),
                RedisKeys.abuse(tenantId, clientIp));

        try {
            List<?> result = algorithm == RateLimitAlgorithm.GCRA
                    ? redisTemplate.execute(RateLimitScripts.GCRA_ADMISSION, keys,
                            String.valueOf(now),
                            String.valueOf(window.toMillis()),
                            String.valueOf(rateLimit.getHighTrustLimit()),
                            String.valueOf(rateLimit.getMediumTrustLimit()),
                            String.valueOf(rateLimit.getLowTrustLimit()),
                            rateLimit.isEnabled() ? "1" : "0",
                            String.valueOf(effectiveCost),
                            String.valueOf(abuseLimit))
                    : redisTemplate.execute(RateLimitScripts.SORTED_SET_ADMISSION, keys,
                            String.valueOf(now),
                            String.valueOf(window.toMillis()),
                            String.valueOf(rateLimit.getHighTrustLimit()),
                            String.valueOf(rateLimit.getMediumTrustLimit()),
                            String.valueOf(rateLimit.getLowTrustLimit()),
                            rateLimit.isEnabled() ? "1" : "0",
                            requestId + ":" + now,
                            String.valueOf(effectiveCost),
                            String.valueOf(abuseLimit));

            if (result == null || result.size() < 6) {
                throw new IllegalStateException("Admission script returned an unexpected result");
            }

            boolean allowed = asLong(result.get(0)) == 1L;
            TrustTier tier = parseTier(result.get(1));
            int limit = (int) Math.max(0, asLong(result.get(2)));
            int remaining = (int) Math.max(0, asLong(result.get(3)));
            long retryAfter = Math.max(0, asLong(result.get(4)));
            LimitReason reason = parseReason(result.get(5));

            return new Decision(allowed, tier, limit, remaining, retryAfter, reason);
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

    public RateLimitAlgorithm algorithm() {
        return algorithm;
    }

    private GatewayRequestException unavailable() {
        return new GatewayRequestException(HttpStatus.SERVICE_UNAVAILABLE,
                "rate_limiter_unavailable",
                "Request admission is temporarily unavailable. Retry shortly.");
    }

    private LimitReason parseReason(Object value) {
        if (value == null) {
            return LimitReason.OK;
        }
        try {
            return LimitReason.valueOf(String.valueOf(value));
        } catch (IllegalArgumentException unknown) {
            log.warn("Ignoring unknown admission reason '{}'", value);
            return LimitReason.OK;
        }
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
