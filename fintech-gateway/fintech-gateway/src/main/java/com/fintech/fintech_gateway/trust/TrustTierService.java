package com.fintech.fintech_gateway.trust;

import com.fintech.fintech_gateway.persistence.PersistenceService;
import com.fintech.fintech_gateway.redis.RedisKeys;
import com.fintech.fintech_gateway.web.GatewayRequestException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

/**
 * Adaptive trust tiers backed by Redis.
 *
 * <p>Trust is scoped to tenant + caller IP. The same address can be a clean
 * customer in one tenant and a repeat offender in another, so a global
 * per-IP tier was both a false-positive source and a cross-tenant signal
 * leak.</p>
 *
 * <p>The admission read path lives in {@code AdmissionGate}, which resolves the
 * blacklist and tier inside the same Lua call as the rate-limit check. This
 * service owns the writes: penalties (blocks, throttles) and the asynchronous
 * clean-traffic counter.</p>
 *
 * <p>Writes are best-effort because the request decision has already been
 * made; failures are logged and the durable audit trail still records the
 * security event. Operator resets fail loudly instead, because an operator
 * must know the reset did not apply.</p>
 */
@Service
public class TrustTierService {

    private static final Logger log = LoggerFactory.getLogger(TrustTierService.class);

    private static final int BLACKLIST_THRESHOLD = 4;
    private static final int GOOD_BEHAVIOUR_THRESHOLD = 100;
    private static final int THROTTLE_DEGRADE_THRESHOLD = 6;

    private static final Duration BLOCK_WINDOW = Duration.ofHours(24);
    private static final Duration THROTTLE_WINDOW = Duration.ofMinutes(10);
    private static final Duration GOOD_BEHAVIOUR_WINDOW = Duration.ofHours(24);
    private static final Duration HIGH_TRUST_TTL = Duration.ofDays(7);

    private static final DefaultRedisScript<Long> INCREMENT_WITH_TTL = new DefaultRedisScript<>("""
            local count = redis.call('INCR', KEYS[1])
            if count == 1 then
              redis.call('EXPIRE', KEYS[1], ARGV[1])
            end
            return count
            """, Long.class);

    /**
     * Records a clean request in one round trip: clears the throttle counter
     * and, for MEDIUM callers only, increments the good-behaviour counter.
     *
     * <p>KEYS: good-behaviour counter, throttle counter.
     * ARGV: current tier, good-behaviour TTL (seconds).</p>
     */
    private static final DefaultRedisScript<Long> RECORD_SUCCESS = new DefaultRedisScript<>("""
            redis.call('DEL', KEYS[2])
            if ARGV[1] ~= 'MEDIUM' then
              return 0
            end
            local count = redis.call('INCR', KEYS[1])
            if count == 1 then
              redis.call('EXPIRE', KEYS[1], ARGV[2])
            end
            return count
            """, Long.class);

    public enum TrustTier {
        HIGH, MEDIUM, LOW, BLACKLISTED
    }

    private final StringRedisTemplate redisTemplate;
    private final PersistenceService persistenceService;

    public TrustTierService(StringRedisTemplate redisTemplate,
                            PersistenceService persistenceService) {
        this.redisTemplate = redisTemplate;
        this.persistenceService = persistenceService;
    }

    public void recordBlock(String tenantId, String ip) {
        try {
            TrustTier previous = currentTierBestEffort(tenantId, ip);
            Long blockCount = incrementWithTtl(RedisKeys.blockCount(tenantId, ip), BLOCK_WINDOW);
            if (blockCount == null) {
                return;
            }
            if (blockCount >= BLACKLIST_THRESHOLD) {
                redisTemplate.opsForValue()
                        .set(RedisKeys.blacklist(tenantId, ip), "PERMANENT");
                persistenceService.saveTrustHistory(tenantId, ip, previous.name(),
                        TrustTier.BLACKLISTED.name(),
                        "Blocked " + blockCount + " times within 24 hours");
                log.warn("IP {} permanently blacklisted in tenant {} after {} blocks",
                        ip, tenantId, blockCount);
            } else {
                Duration cooldown = cooldownFor(blockCount.intValue());
                redisTemplate.opsForValue()
                        .set(RedisKeys.trust(tenantId, ip), TrustTier.LOW.name(), cooldown);
                persistenceService.saveTrustHistory(tenantId, ip, previous.name(),
                        TrustTier.LOW.name(),
                        "Blocked " + blockCount + " times; cooldown " + cooldown);
                log.info("IP {} downgraded to LOW trust in tenant {} for {}",
                        ip, tenantId, cooldown);
            }
        } catch (RuntimeException e) {
            // The request was already blocked; failing to persist the
            // downgrade must not change that decision.
            log.error("Failed to record block for tenant {} ip {}", tenantId, ip, e);
        }
    }

    /**
     * Records a successful request off the request thread. The admission
     * decision has already been made, so this is best-effort: a failure here
     * must never turn a served request into an error.
     *
     * <p>The tier is passed in rather than re-read, because the admission
     * script already resolved it. Re-reading it would add another round trip
     * and could observe a tier change that happened after the decision.</p>
     */
    @Async("trustExecutor")
    public void recordSuccess(String tenantId, String ip, TrustTier tier) {
        try {
            Long goodCount = redisTemplate.execute(RECORD_SUCCESS,
                    List.of(RedisKeys.goodCount(tenantId, ip),
                            RedisKeys.throttleCount(tenantId, ip)),
                    tier.name(),
                    String.valueOf(GOOD_BEHAVIOUR_WINDOW.toSeconds()));
            if (goodCount != null && goodCount >= GOOD_BEHAVIOUR_THRESHOLD) {
                redisTemplate.opsForValue()
                        .set(RedisKeys.trust(tenantId, ip), TrustTier.HIGH.name(), HIGH_TRUST_TTL);
                redisTemplate.delete(RedisKeys.goodCount(tenantId, ip));
                persistenceService.saveTrustHistory(tenantId, ip,
                        TrustTier.MEDIUM.name(), TrustTier.HIGH.name(),
                        "Sustained clean traffic");
                log.info("IP {} upgraded to HIGH trust in tenant {}", ip, tenantId);
            }
        } catch (RuntimeException e) {
            log.error("Failed to record successful request for tenant {} ip {}",
                    tenantId, ip, e);
        }
    }

    /**
     * Records a throttle event and downgrades repeat offenders. Returns the
     * number of throttle events in the current window.
     */
    public int recordThrottle(String tenantId, String ip) {
        try {
            Long count = incrementWithTtl(
                    RedisKeys.throttleCount(tenantId, ip), THROTTLE_WINDOW);
            if (count == null) {
                return 0;
            }
            if (count >= THROTTLE_DEGRADE_THRESHOLD) {
                redisTemplate.opsForValue()
                        .set(RedisKeys.trust(tenantId, ip), TrustTier.LOW.name(),
                                Duration.ofMinutes(30));
                persistenceService.saveTrustHistory(tenantId, ip, "UNKNOWN",
                        TrustTier.LOW.name(),
                        "Repeated throttle violations: " + count);
                log.info("IP {} downgraded to LOW trust in tenant {} after {} throttles",
                        ip, tenantId, count);
            }
            return count.intValue();
        } catch (RuntimeException e) {
            log.error("Failed to record throttle for tenant {} ip {}", tenantId, ip, e);
            return 0;
        }
    }

    /**
     * Operator action: clears every gateway penalty for a tenant + IP and
     * restores the default MEDIUM trust tier. Unlike the automatic writes
     * above this fails loudly, because an operator must know the reset did not
     * apply.
     */
    public void resetToMedium(String tenantId, String ip, String reason) {
        TrustTier previous = currentTierBestEffort(tenantId, ip);
        try {
            redisTemplate.delete(List.of(
                    RedisKeys.blacklist(tenantId, ip),
                    RedisKeys.blockCount(tenantId, ip),
                    RedisKeys.throttleCount(tenantId, ip),
                    RedisKeys.goodCount(tenantId, ip),
                    RedisKeys.trust(tenantId, ip)));
        } catch (DataAccessException e) {
            log.error("Failed to reset trust state for tenant {} ip {}", tenantId, ip, e);
            throw new GatewayRequestException(HttpStatus.SERVICE_UNAVAILABLE,
                    "trust_store_unavailable",
                    "Trust state could not be reset. Retry shortly.");
        }
        persistenceService.saveTrustHistory(tenantId, ip, previous.name(),
                TrustTier.MEDIUM.name(), reason);
        log.info("Trust state reset to MEDIUM for tenant {} ip {} ({})",
                tenantId, ip, reason);
    }

    public int getRiskAdjustment(TrustTier tier) {
        return switch (tier) {
            case HIGH -> -10;
            case MEDIUM -> 0;
            case LOW -> 20;
            case BLACKLISTED -> 100;
        };
    }

    private Long incrementWithTtl(String key, Duration ttl) {
        return redisTemplate.execute(INCREMENT_WITH_TTL, List.of(key),
                String.valueOf(ttl.toSeconds()));
    }

    private TrustTier currentTierBestEffort(String tenantId, String ip) {
        try {
            return parseTier(redisTemplate.opsForValue().get(RedisKeys.trust(tenantId, ip)));
        } catch (RuntimeException e) {
            return TrustTier.MEDIUM;
        }
    }

    private TrustTier parseTier(String value) {
        if (value == null) {
            return TrustTier.MEDIUM;
        }
        try {
            return TrustTier.valueOf(value);
        } catch (IllegalArgumentException e) {
            log.warn("Ignoring unknown trust tier value '{}'", value);
            return TrustTier.MEDIUM;
        }
    }

    private Duration cooldownFor(int blockCount) {
        return switch (blockCount) {
            case 1 -> Duration.ofMinutes(1);
            case 2 -> Duration.ofMinutes(10);
            case 3 -> Duration.ofHours(1);
            default -> Duration.ofHours(24);
        };
    }
}
