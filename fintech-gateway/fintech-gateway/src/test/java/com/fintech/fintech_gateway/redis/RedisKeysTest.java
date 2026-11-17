package com.fintech.fintech_gateway.redis;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedisKeysTest {

    private static final String TENANT = "tenant-a";
    private static final String IP = "203.0.113.5";
    private static final String CLIENT = "client-9";

    @Test
    void everyTenantKeySharesOneClusterHashTag() {
        String tag = RedisKeys.tenantTag(TENANT);
        List<String> keys = List.of(
                RedisKeys.blacklist(TENANT, IP),
                RedisKeys.trust(TENANT, IP),
                RedisKeys.blockCount(TENANT, IP),
                RedisKeys.throttleCount(TENANT, IP),
                RedisKeys.goodCount(TENANT, IP),
                RedisKeys.quota(TENANT, CLIENT),
                RedisKeys.abuse(TENANT, IP));

        for (String key : keys) {
            assertTrue(key.contains(tag), key);
            assertEquals(key.indexOf('{'), key.lastIndexOf('{'), key);
            assertEquals(key.indexOf('}'), key.lastIndexOf('}'), key);
        }
    }

    @Test
    void tenantTagIsStableAndTenantSpecific() {
        assertEquals(RedisKeys.tenantTag(TENANT), RedisKeys.tenantTag(TENANT));
        assertNotEquals(RedisKeys.tenantTag(TENANT), RedisKeys.tenantTag("tenant-b"));
    }

    @Test
    void aTenantSuppliedBraceCannotEscapeTheHashTag() {
        String malicious = "}{t:0000000000000000}";

        String tag = RedisKeys.tenantTag(malicious);

        assertTrue(tag.matches("\\{t:[0-9a-f]{16}}"), tag);
        assertEquals(1, tag.length() - tag.replace("{", "").length());
        assertEquals(1, tag.length() - tag.replace("}", "").length());
    }

    @Test
    void blankTenantFallsBackToTheDefaultTenant() {
        assertEquals(RedisKeys.tenantTag("default"), RedisKeys.tenantTag(null));
        assertEquals(RedisKeys.tenantTag("default"), RedisKeys.tenantTag("   "));
    }

    @Test
    void quotaDoesNotEmbedTheRawClientId() {
        String key = RedisKeys.quota(TENANT, CLIENT);

        assertFalse(key.contains(CLIENT), key);
        assertTrue(key.matches(".*:quota:[0-9a-f]{32}$"), key);
    }

    @Test
    void sameClientInDifferentTenantsGetsDifferentQuotaKeys() {
        assertNotEquals(RedisKeys.quota(TENANT, CLIENT),
                RedisKeys.quota("tenant-b", CLIENT));
    }

    @Test
    void blankClientUsesTheAnonymousBucket() {
        assertEquals(RedisKeys.quota(TENANT, "anonymous"), RedisKeys.quota(TENANT, null));
        assertEquals(RedisKeys.quota(TENANT, "anonymous"), RedisKeys.quota(TENANT, " "));
    }

    @Test
    void quotaIsIndependentOfTheCallerIp() {
        assertEquals(RedisKeys.quota(TENANT, CLIENT),
                RedisKeys.quota(TENANT, CLIENT));
        assertNotEquals(RedisKeys.quota(TENANT, CLIENT), RedisKeys.abuse(TENANT, IP));
    }

    @Test
    void namespaceIsVersioned() {
        assertTrue(RedisKeys.trust(TENANT, IP).contains("gw:v2:"));
    }
}
