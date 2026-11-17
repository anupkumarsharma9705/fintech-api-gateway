package com.fintech.fintech_gateway.redis;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Every Redis key the gateway writes, in one place.
 *
 * <p>Admission state is tenant-scoped. Redis Cluster only executes a
 * multi-key script when every key hashes to the same slot, so all keys for a
 * tenant share a {@code {t:<hash>}} hash tag. The tag uses a hash rather than
 * the raw tenant id so an operator-supplied tenant string can never inject a
 * brace and move a key into another slot.</p>
 *
 * <p>The {@code gw:v2:} prefix is deliberate: key layout and limiter state
 * changed with tenant keying and GCRA, and a mixed-version rolling deploy must
 * not interpret the previous format. v1 keys simply expire.</p>
 */
public final class RedisKeys {

    private static final String PREFIX = "gw:v2:";
    private static final String DEFAULT_TENANT = "default";
    private static final String ANONYMOUS_CLIENT = "anonymous";
    private static final int TENANT_TAG_HASH_CHARS = 16;
    private static final int CLIENT_HASH_CHARS = 32;

    private static final ThreadLocal<MessageDigest> SHA256 =
            ThreadLocal.withInitial(RedisKeys::newDigest);

    private RedisKeys() {
    }

    /** Cluster hash tag shared by every key belonging to one tenant. */
    public static String tenantTag(String tenantId) {
        String tenant = normalize(tenantId, DEFAULT_TENANT);
        return "{t:" + sha256Hex(tenant).substring(0, TENANT_TAG_HASH_CHARS) + "}";
    }

    /** Permanent blacklist entry for a tenant + caller IP. */
    public static String blacklist(String tenantId, String ip) {
        return key(tenantId, ":blacklist:" + ip);
    }

    /** Current trust tier for a tenant + caller IP. */
    public static String trust(String tenantId, String ip) {
        return key(tenantId, ":trust:" + ip);
    }

    public static String blockCount(String tenantId, String ip) {
        return key(tenantId, ":blockcount:" + ip);
    }

    public static String throttleCount(String tenantId, String ip) {
        return key(tenantId, ":throttlecount:" + ip);
    }

    public static String goodCount(String tenantId, String ip) {
        return key(tenantId, ":goodcount:" + ip);
    }

    /**
     * Quota bucket for a tenant + authenticated client. The client id comes
     * from a signed token claim and is hashed: the claim is attacker-influenced
     * in the sense that a misconfigured issuer could emit separators or an
     * unbounded string, and neither belongs in a key.
     */
    public static String quota(String tenantId, String clientId) {
        String client = normalize(clientId, ANONYMOUS_CLIENT);
        String digest = sha256Hex(client).substring(0, CLIENT_HASH_CHARS);
        return key(tenantId, ":quota:" + digest);
    }

    /**
     * Coarse anti-abuse ceiling for a tenant + caller IP. This is intentionally
     * separate from the tenant + client quota: a misbehaving client cannot
     * exhaust another client's quota, and a single IP cannot hide behind many
     * tenant headers (the tenant is a signed claim in production).
     */
    public static String abuse(String tenantId, String ip) {
        return key(tenantId, ":abuse:" + ip);
    }

    private static String key(String tenantId, String suffix) {
        return PREFIX + tenantTag(tenantId) + suffix;
    }

    private static String normalize(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String sha256Hex(String value) {
        MessageDigest digest = SHA256.get();
        digest.reset();
        byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(bytes);
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the JVM", e);
        }
    }
}
