package com.fintech.fintech_gateway.idempotency;

import com.fintech.fintech_gateway.config.GatewayProperties;
import com.fintech.fintech_gateway.web.GatewayRequestException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Atomic, tenant-scoped idempotency.
 *
 * <p>The previous implementation performed a read-then-write check, so two
 * concurrent retries of the same payment could both pass the "not processed"
 * check and charge the customer twice. This implementation uses Redis
 * SET NX EX to claim the key and Lua compare-and-set/delete scripts to
 * complete or release it, which makes the claim atomic across gateway
 * instances.</p>
 *
 * <p>Redis failures are deliberately not swallowed: a payment gateway that
 * cannot prove idempotency must reject the request rather than risk a
 * duplicate charge.</p>
 */
@Service
public class IdempotencyService {

    public enum Acquisition {
        ACQUIRED,
        IN_PROGRESS,
        COMPLETED
    }

    /**
     * A completed response, stored as raw bytes rather than a String. The
     * proxy forwards the upstream {@code Content-Encoding}, so a replayed
     * body may be gzip-compressed or binary; decoding it to text would
     * corrupt it. Jackson serializes {@code byte[]} as Base64, which is
     * safe inside the Redis JSON envelope.
     */
    public record StoredResponse(int status, String contentType,
                                 String contentEncoding, byte[] body) {
        public StoredResponse {
            body = body == null ? null : body.clone();
        }
    }

    public record Result(Acquisition state, StoredResponse response, String ownerToken) {
        public static Result acquired(String ownerToken) {
            return new Result(Acquisition.ACQUIRED, null, ownerToken);
        }

        public static Result inProgress() {
            return new Result(Acquisition.IN_PROGRESS, null, null);
        }

        public static Result completed(StoredResponse response) {
            return new Result(Acquisition.COMPLETED, response, null);
        }
    }

    private static final String KEY_PREFIX = "idem:";
    private static final String IN_PROGRESS_PREFIX = "IN_PROGRESS:";
    private static final Pattern KEY_PATTERN = Pattern.compile("^[A-Za-z0-9._:-]+$");

    private static final DefaultRedisScript<Long> COMPLETE_SCRIPT = new DefaultRedisScript<>("""
            local current = redis.call('GET', KEYS[1])
            if current == false or current ~= ARGV[1] then
              return 0
            end
            redis.call('SET', KEYS[1], ARGV[2], 'PX', ARGV[3])
            return 1
            """, Long.class);

    private static final DefaultRedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>("""
            local current = redis.call('GET', KEYS[1])
            if current == ARGV[1] then
              return redis.call('DEL', KEYS[1])
            end
            return 0
            """, Long.class);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final GatewayProperties properties;

    public IdempotencyService(StringRedisTemplate redisTemplate,
                              ObjectMapper objectMapper,
                              GatewayProperties properties) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    /**
     * Atomically claims the key. Returns COMPLETED with the original response
     * when the key was already finished, IN_PROGRESS when another request
     * currently owns it, and ACQUIRED when the caller may execute the
     * operation.
     */
    public Result acquire(String tenantId, String idempotencyKey) {
        String redisKey = redisKey(tenantId, idempotencyKey);
        String ownerToken = UUID.randomUUID().toString();
        String inProgressValue = IN_PROGRESS_PREFIX + ownerToken;

        Boolean claimed = redisTemplate.opsForValue()
                .setIfAbsent(redisKey, inProgressValue, properties.getIdempotency().getTtl());
        if (Boolean.TRUE.equals(claimed)) {
            return Result.acquired(ownerToken);
        }

        String current = redisTemplate.opsForValue().get(redisKey);
        if (current == null) {
            // The key expired between SET NX and GET. Retry the claim once.
            Boolean retried = redisTemplate.opsForValue()
                    .setIfAbsent(redisKey, inProgressValue, properties.getIdempotency().getTtl());
            if (Boolean.TRUE.equals(retried)) {
                return Result.acquired(ownerToken);
            }
            current = redisTemplate.opsForValue().get(redisKey);
        }

        if (current == null || isInProgress(current)) {
            return Result.inProgress();
        }
        return Result.completed(deserialize(current));
    }

    /**
     * Replaces the in-progress marker with the serialized response, but only
     * if this caller still owns the key. The owner token matters: if the
     * claim expired and another request re-claimed the key, a stale caller
     * must not overwrite the newer caller's record.
     */
    public boolean complete(String tenantId, String idempotencyKey, String ownerToken,
                            StoredResponse response) {
        String payload = serialize(response);
        String ttlMillis = String.valueOf(properties.getIdempotency().getTtl().toMillis());
        Long completed = redisTemplate.execute(COMPLETE_SCRIPT,
                List.of(redisKey(tenantId, idempotencyKey)),
                IN_PROGRESS_PREFIX + ownerToken, payload, ttlMillis);
        return completed != null && completed == 1L;
    }

    /**
     * Releases a claim after a failed or non-2xx execution so the client can
     * safely retry. Only the owner may release the key; a stale owner must
     * not delete a newer caller's claim.
     */
    public boolean release(String tenantId, String idempotencyKey, String ownerToken) {
        Long released = redisTemplate.execute(RELEASE_SCRIPT,
                List.of(redisKey(tenantId, idempotencyKey)),
                IN_PROGRESS_PREFIX + ownerToken);
        return released != null && released == 1L;
    }

    public void validateKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new GatewayRequestException(HttpStatus.BAD_REQUEST,
                    "idempotency_key_required",
                    "An Idempotency-Key header is required for this operation.");
        }
        int maxLength = properties.getIdempotency().getMaxKeyLength();
        if (idempotencyKey.length() > maxLength) {
            throw new GatewayRequestException(HttpStatus.BAD_REQUEST,
                    "invalid_idempotency_key",
                    "Idempotency-Key must be at most " + maxLength + " characters.");
        }
        if (!KEY_PATTERN.matcher(idempotencyKey).matches()) {
            throw new GatewayRequestException(HttpStatus.BAD_REQUEST,
                    "invalid_idempotency_key",
                    "Idempotency-Key contains unsupported characters.");
        }
    }

    private String redisKey(String tenantId, String idempotencyKey) {
        return KEY_PREFIX + tenantId + ":" + idempotencyKey;
    }

    private boolean isInProgress(String value) {
        // The legacy marker (no owner token) is still treated as in progress
        // so a rolling deploy cannot execute an operation twice.
        return "IN_PROGRESS".equals(value) || value.startsWith(IN_PROGRESS_PREFIX);
    }

    private String serialize(StoredResponse response) {
        try {
            return objectMapper.writeValueAsString(response);
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to serialize idempotent response", e);
        }
    }

    private StoredResponse deserialize(String payload) {
        try {
            return objectMapper.readValue(payload, StoredResponse.class);
        } catch (JacksonException e) {
            // A malformed stored value must not silently execute the
            // operation again. Fail closed and let the caller retry with a
            // fresh key after the TTL expires.
            throw new GatewayRequestException(HttpStatus.CONFLICT,
                    "idempotency_state_corrupt",
                    "The stored idempotency record is unreadable. Retry with a new key.");
        }
    }
}
