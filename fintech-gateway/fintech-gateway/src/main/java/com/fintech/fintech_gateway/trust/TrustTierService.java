package com.fintech.fintech_gateway.trust;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.fintech.fintech_gateway.persistence.PersistenceService;
import com.fintech.fintech_gateway.web.GatewayRequestException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

/**
 * Adaptive trust tiers backed by Redis.
 *
 * <p>Reads fail closed: if Redis cannot answer whether an IP is blacklisted,
 * the gateway rejects the request instead of guessing that the caller is
 * trustworthy. Writes (recording blocks/throttles/good behaviour) are
 * best-effort because the request decision has already been made; failures
 * are logged and the durable audit trail still records the security event.</p>
 *
 * <p>Only the non-blacklist tier is cached, for at most 30 seconds, and every
 * mutation invalidates the entry. Blacklist state is never cached.</p>
 */
@Service
public class TrustTierService {

    private static final Logger log = LoggerFactory.getLogger(TrustTierService.class);

    private static final String TRUST_KEY = "trust:";
    private static final String BLOCK_COUNT_KEY = "blockcount:";
    private static final String BLACKLIST_KEY = "blacklist:";
    private static final String THROTTLE_COUNT_KEY = "throttlecount:";
    private static final String GOOD_COUNT_KEY = "goodcount:";

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

    public enum TrustTier {
        HIGH, MEDIUM, LOW, BLACKLISTED
    }

    private final StringRedisTemplate redisTemplate;
    private final PersistenceService persistenceService;
    private final Cache<String, TrustTier> tierCache;

    public TrustTierService(StringRedisTemplate redisTemplate,
                            PersistenceService persistenceService) {
        this.redisTemplate = redisTemplate;
        this.persistenceService = persistenceService;
        this.tierCache = Caffeine.newBuilder()
                .maximumSize(100_000)
                .expireAfterWrite(Duration.ofSeconds(30))
                .build();
    }

    public TrustTier getTrustTier(String ip) {
        if (isBlacklisted(ip)) {
            return TrustTier.BLACKLISTED;
        }
        TrustTier cached = tierCache.getIfPresent(ip);
        if (cached != null) {
            return cached;
        }
        TrustTier resolved;
        try {
            String tier = redisTemplate.opsForValue().get(TRUST_KEY + ip);
            resolved = parseTier(tier);
        } catch (DataAccessException e) {
            log.error("Trust store unavailable while resolving tier for {}", ip, e);
            throw new GatewayRequestException(HttpStatus.SERVICE_UNAVAILABLE,
                    "trust_store_unavailable",
                    "Request screening is temporarily unavailable. Retry shortly.");
        }
        tierCache.put(ip, resolved);
        return resolved;
    }

    public boolean isBlacklisted(String ip) {
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(BLACKLIST_KEY + ip));
        } catch (DataAccessException e) {
            log.error("Trust store unavailable while checking blacklist for {}", ip, e);
            throw new GatewayRequestException(HttpStatus.SERVICE_UNAVAILABLE,
                    "trust_store_unavailable",
                    "Request screening is temporarily unavailable. Retry shortly.");
        }
    }

    public void recordBlock(String ip) {
        try {
            TrustTier previous = currentTierBestEffort(ip);
            Long blockCount = incrementWithTtl(BLOCK_COUNT_KEY + ip, BLOCK_WINDOW);
            if (blockCount == null) {
                return;
            }
            if (blockCount >= BLACKLIST_THRESHOLD) {
                redisTemplate.opsForValue().set(BLACKLIST_KEY + ip, "PERMANENT");
                persistenceService.saveTrustHistory(ip, previous.name(), TrustTier.BLACKLISTED.name(),
                        "Blocked " + blockCount + " times within 24 hours");
                log.warn("IP {} permanently blacklisted after {} blocks", ip, blockCount);
            } else {
                Duration cooldown = cooldownFor(blockCount.intValue());
                redisTemplate.opsForValue().set(TRUST_KEY + ip, TrustTier.LOW.name(), cooldown);
                persistenceService.saveTrustHistory(ip, previous.name(), TrustTier.LOW.name(),
                        "Blocked " + blockCount + " times; cooldown " + cooldown);
                log.info("IP {} downgraded to LOW trust for {}", ip, cooldown);
            }
            tierCache.invalidate(ip);
        } catch (RuntimeException e) {
            // The request was already blocked; failing to persist the
            // downgrade must not change that decision.
            log.error("Failed to record block for {}", ip, e);
        }
    }

    public void recordGoodBehaviour(String ip) {
        try {
            TrustTier current = getTrustTier(ip);
            if (current != TrustTier.MEDIUM) {
                return;
            }
            Long goodCount = incrementWithTtl(GOOD_COUNT_KEY + ip, GOOD_BEHAVIOUR_WINDOW);
            if (goodCount != null && goodCount >= GOOD_BEHAVIOUR_THRESHOLD) {
                redisTemplate.opsForValue()
                        .set(TRUST_KEY + ip, TrustTier.HIGH.name(), HIGH_TRUST_TTL);
                redisTemplate.delete(GOOD_COUNT_KEY + ip);
                tierCache.invalidate(ip);
                persistenceService.saveTrustHistory(ip, TrustTier.MEDIUM.name(), TrustTier.HIGH.name(),
                        "Sustained clean traffic");
                log.info("IP {} upgraded to HIGH trust", ip);
            }
        } catch (RuntimeException e) {
            log.error("Failed to record good behaviour for {}", ip, e);
        }
    }

    /**
     * Records a throttle event and downgrades repeat offenders. Returns the
     * number of throttle events in the current window.
     */
    public int recordThrottle(String ip) {
        try {
            Long count = incrementWithTtl(THROTTLE_COUNT_KEY + ip, THROTTLE_WINDOW);
            if (count == null) {
                return 0;
            }
            if (count >= THROTTLE_DEGRADE_THRESHOLD) {
                redisTemplate.opsForValue()
                        .set(TRUST_KEY + ip, TrustTier.LOW.name(), Duration.ofMinutes(30));
                tierCache.invalidate(ip);
                persistenceService.saveTrustHistory(ip, "UNKNOWN", TrustTier.LOW.name(),
                        "Repeated throttle violations: " + count);
                log.info("IP {} downgraded to LOW trust after {} throttles", ip, count);
            }
            return count.intValue();
        } catch (RuntimeException e) {
            log.error("Failed to record throttle for {}", ip, e);
            return 0;
        }
    }

    public void resetThrottleCount(String ip) {
        try {
            redisTemplate.delete(THROTTLE_COUNT_KEY + ip);
        } catch (RuntimeException e) {
            log.error("Failed to reset throttle count for {}", ip, e);
        }
    }

    /**
     * Operator action: clears every gateway penalty for an IP and restores
     * the default MEDIUM trust tier. Unlike the automatic writes above this
     * fails loudly, because an operator must know the reset did not apply.
     */
    public void resetToMedium(String ip, String reason) {
        TrustTier previous = currentTierBestEffort(ip);
        try {
            redisTemplate.delete(List.of(
                    BLACKLIST_KEY + ip,
                    BLOCK_COUNT_KEY + ip,
                    THROTTLE_COUNT_KEY + ip,
                    GOOD_COUNT_KEY + ip,
                    TRUST_KEY + ip));
        } catch (DataAccessException e) {
            log.error("Failed to reset trust state for {}", ip, e);
            throw new GatewayRequestException(HttpStatus.SERVICE_UNAVAILABLE,
                    "trust_store_unavailable",
                    "Trust state could not be reset. Retry shortly.");
        }
        tierCache.invalidate(ip);
        persistenceService.saveTrustHistory(ip, previous.name(), TrustTier.MEDIUM.name(), reason);
        log.info("Trust state reset to MEDIUM for {} ({})", ip, reason);
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

    private TrustTier currentTierBestEffort(String ip) {
        TrustTier cached = tierCache.getIfPresent(ip);
        if (cached != null) {
            return cached;
        }
        try {
            return parseTier(redisTemplate.opsForValue().get(TRUST_KEY + ip));
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
