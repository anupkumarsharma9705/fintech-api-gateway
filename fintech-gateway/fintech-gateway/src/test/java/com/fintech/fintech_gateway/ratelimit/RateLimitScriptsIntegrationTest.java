package com.fintech.fintech_gateway.ratelimit;

import com.fintech.fintech_gateway.redis.RedisKeys;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract tests against a real Redis, using the exact Lua that ships.
 *
 * <p>The mock-based unit tests can only prove how Java maps a script result;
 * they cannot prove that the script counts a burst, refills, or caps a bucket.
 * Those are the properties a rate limiter is bought for, so they are verified
 * against a real server.</p>
 *
 * <p>The test is opt-in because the default suite must run without external
 * services. Start a disposable Redis and run it explicitly:</p>
 *
 * <pre>{@code
 * docker run --rm -d --name gw-redis-it -p 6390:6379 redis:7.4-alpine
 * GATEWAY_REDIS_IT=true GATEWAY_IT_REDIS_PORT=6390 mvn -pl fintech-gateway/fintech-gateway test \
 *   -Dtest=RateLimitScriptsIntegrationTest
 * }</pre>
 *
 * <p>This is a script-contract test, not the full Testcontainers suite still
 * listed as an open gap.</p>
 */
@EnabledIfEnvironmentVariable(named = "GATEWAY_REDIS_IT", matches = "true")
class RateLimitScriptsIntegrationTest {

    private static final long WINDOW_MS = 60_000;
    private static final long NOW = 1_765_000_000_000L;

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redis;

    private String tenant;

    @BeforeAll
    static void connect() {
        String host = System.getenv().getOrDefault("GATEWAY_IT_REDIS_HOST", "127.0.0.1");
        int port = Integer.parseInt(
                System.getenv().getOrDefault("GATEWAY_IT_REDIS_PORT", "6379"));
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(host, port);
        connectionFactory = new LettuceConnectionFactory(config);
        connectionFactory.afterPropertiesSet();
        connectionFactory.start();
        redis = new StringRedisTemplate(connectionFactory);
        redis.afterPropertiesSet();
    }

    @AfterAll
    static void disconnect() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @BeforeEach
    void uniqueTenant() {
        tenant = "it-" + UUID.randomUUID().toString().substring(0, 12);
    }

    @Test
    void aFreshBucketAdmitsExactlyTheConfiguredBurst() {
        String key = quotaKey();

        for (int i = 0; i < 10; i++) {
            List<?> result = gcraSingle(key, NOW, WINDOW_MS, 10, 1);
            assertEquals(1L, asLong(result.get(0)), "request " + (i + 1));
            assertEquals(9 - i, asLong(result.get(1)), "remaining after request " + (i + 1));
        }

        List<?> rejected = gcraSingle(key, NOW, WINDOW_MS, 10, 1);
        assertEquals(0L, asLong(rejected.get(0)));
        assertEquals(0L, asLong(rejected.get(1)));
        assertTrue(asLong(rejected.get(2)) >= 1);
    }

    @Test
    void aCostCannotBorrowSlotsBeyondTheWindow() {
        String key = quotaKey();
        for (int i = 0; i < 8; i++) {
            assertEquals(1L, asLong(gcraSingle(key, NOW, WINDOW_MS, 10, 1).get(0)));
        }

        List<?> tooExpensive = gcraSingle(key, NOW, WINDOW_MS, 10, 3);
        assertEquals(0L, asLong(tooExpensive.get(0)));
        assertEquals(2L, asLong(tooExpensive.get(1)), "two slots were still free");

        List<?> fits = gcraSingle(key, NOW, WINDOW_MS, 10, 2);
        assertEquals(1L, asLong(fits.get(0)));
        assertEquals(0L, asLong(fits.get(1)));
        assertEquals(0L, asLong(gcraSingle(key, NOW, WINDOW_MS, 10, 1).get(0)));
    }

    @Test
    void subMillisecondEmissionIntervalsDoNotCollapseToSixtyThousandPerMinute() {
        String key = quotaKey();

        List<?> result = gcraSingle(key, NOW, WINDOW_MS, 1_000_000, 1);

        assertEquals(1L, asLong(result.get(0)));
        assertEquals(999_999L, asLong(result.get(1)),
                "one request must consume one slot, not a whole millisecond");
    }

    @Test
    void aSlotRefillsAfterOneEmissionInterval() {
        String key = quotaKey();
        for (int i = 0; i < 10; i++) {
            assertEquals(1L, asLong(gcraSingle(key, NOW, 1_000, 10, 1).get(0)));
        }

        assertEquals(0L, asLong(gcraSingle(key, NOW, 1_000, 10, 1).get(0)));
        assertEquals(0L, asLong(gcraSingle(key, NOW + 99, 1_000, 10, 1).get(0)),
                "one millisecond before the emission interval is still empty");
        assertEquals(1L, asLong(gcraSingle(key, NOW + 100, 1_000, 10, 1).get(0)));
    }

    @Test
    void gcraRefillsWithinTheWindowWhileTheSortedSetBoundStaysExact() {
        String gcraKey = RedisKeys.quota(tenant, "gcra-refill-client");
        for (int i = 0; i < 10; i++) {
            assertEquals(1L, asLong(gcraSingle(gcraKey, NOW, 1_000, 10, 1).get(0)));
        }
        assertEquals(1L, asLong(gcraSingle(gcraKey, NOW + 100, 1_000, 10, 1).get(0)),
                "GCRA admits one refill per emission interval inside the same window");

        String ip = "203.0.113.90";
        for (int i = 0; i < 10; i++) {
            assertEquals(1L, asLong(sortedSetAdmission(ip, "sorted-client-a", 10, 100, i)
                    .get(0)), "sorted-set request " + (i + 1));
        }
        assertEquals("QUOTA",
                sortedSetAdmission(ip, "sorted-client-a", 10, 100, 10, NOW + 100)
                        .get(5).toString(),
                "the exact window refuses the refill that GCRA allows");
        assertEquals(1L, sortedSetAdmission(ip, "sorted-client-a", 10, 100, 11, NOW + WINDOW_MS)
                .get(0), "the whole burst expires at the window boundary");
    }

    @Test
    void combinedAdmissionHonoursBlacklistAndTrustTier() {
        redis.opsForValue().set(RedisKeys.blacklist(tenant, "203.0.113.50"), "PERMANENT");

        List<?> blocked = admission("203.0.113.50", "client-a", 100, 50, 10, 0, 1);
        assertEquals(0L, asLong(blocked.get(0)));
        assertEquals("BLACKLISTED", blocked.get(1).toString());
        assertEquals("BLACKLISTED", blocked.get(5).toString());

        redis.delete(RedisKeys.blacklist(tenant, "203.0.113.50"));
        redis.opsForValue().set(RedisKeys.trust(tenant, "203.0.113.50"), "LOW");

        for (int i = 0; i < 10; i++) {
            assertEquals(1L, asLong(admission("203.0.113.50", "client-a", 100, 50, 10, 0, 1)
                    .get(0)), "LOW tier request " + (i + 1));
        }
        List<?> quota = admission("203.0.113.50", "client-a", 100, 50, 10, 0, 1);
        assertEquals(0L, asLong(quota.get(0)));
        assertEquals("LOW", quota.get(1).toString());
        assertEquals(10L, asLong(quota.get(2)));
        assertEquals("QUOTA", quota.get(5).toString());
    }

    @Test
    void theAbuseCeilingAdmitsExactlyItsLimitAndNotOneMore() {
        for (int i = 0; i < 5; i++) {
            assertEquals(1L, asLong(admission("203.0.113.60", "client-a", 100, 100, 100, 5, 1)
                    .get(0)), "abuse request " + (i + 1));
        }

        List<?> abuse = admission("203.0.113.60", "client-a", 100, 100, 100, 5, 1);
        assertEquals(0L, asLong(abuse.get(0)));
        assertEquals("ABUSE", abuse.get(5).toString());
        assertTrue(asLong(abuse.get(4)) >= 1);
    }

    @Test
    void quotaIsPerClientWhileAbuseIsPerIp() {
        String ip = "203.0.113.70";
        for (int i = 0; i < 10; i++) {
            assertEquals(1L, asLong(admission(ip, "client-a", 100, 10, 10, 100, 1).get(0)));
        }
        assertEquals("QUOTA", admission(ip, "client-a", 100, 10, 10, 100, 1).get(5).toString());

        // The same IP with a different authenticated client has its own quota.
        assertEquals(1L, asLong(admission(ip, "client-b", 100, 10, 10, 100, 1).get(0)),
                "client-b must not inherit client-a's exhausted quota");
    }

    @Test
    void sortedSetAdmissionMatchesTheSameContract() {
        String quotaIp = "203.0.113.80";
        for (int i = 0; i < 10; i++) {
            assertEquals(1L, asLong(sortedSetAdmission(quotaIp, "client-a", 10, 100, i).get(0)),
                    "request " + (i + 1));
        }
        assertEquals("QUOTA",
                sortedSetAdmission(quotaIp, "client-a", 10, 100, 10).get(5).toString());

        String abuseIp = "203.0.113.81";
        for (int i = 0; i < 5; i++) {
            assertEquals(1L, asLong(sortedSetAdmission(abuseIp, "client-b", 100, 5, i).get(0)),
                    "abuse request " + (i + 1));
        }
        assertEquals("ABUSE",
                sortedSetAdmission(abuseIp, "client-b", 100, 5, 5).get(5).toString());
    }

    private String quotaKey() {
        return RedisKeys.quota(tenant, "client-a");
    }

    private List<?> gcraSingle(String key, long now, long windowMs, int limit, int cost) {
        return redis.execute(RateLimitScripts.GCRA_SINGLE, List.of(key),
                String.valueOf(now), String.valueOf(windowMs),
                String.valueOf(limit), String.valueOf(cost));
    }

    private List<?> admission(String ip, String client, int high, int medium, int low,
                              int abuseLimit, int cost) {
        return redis.execute(RateLimitScripts.GCRA_ADMISSION,
                List.of(
                        RedisKeys.blacklist(tenant, ip),
                        RedisKeys.trust(tenant, ip),
                        RedisKeys.quota(tenant, client),
                        RedisKeys.abuse(tenant, ip)),
                String.valueOf(NOW), String.valueOf(WINDOW_MS),
                String.valueOf(high), String.valueOf(medium), String.valueOf(low),
                "1", String.valueOf(cost), String.valueOf(abuseLimit));
    }

    private List<?> sortedSetAdmission(String ip, String client, int quotaLimit,
                                       int abuseLimit, int sequence) {
        return sortedSetAdmission(ip, client, quotaLimit, abuseLimit, sequence, NOW);
    }

    private List<?> sortedSetAdmission(String ip, String client, int quotaLimit,
                                       int abuseLimit, int sequence, long now) {
        return redis.execute(RateLimitScripts.SORTED_SET_ADMISSION,
                List.of(
                        RedisKeys.blacklist(tenant, ip),
                        RedisKeys.trust(tenant, ip),
                        RedisKeys.quota(tenant, client),
                        RedisKeys.abuse(tenant, ip)),
                String.valueOf(now), String.valueOf(WINDOW_MS),
                String.valueOf(quotaLimit), String.valueOf(quotaLimit),
                String.valueOf(quotaLimit), "1",
                "member-" + sequence, "1", String.valueOf(abuseLimit));
    }

    private static long asLong(Object value) {
        return Long.parseLong(String.valueOf(value));
    }
}
