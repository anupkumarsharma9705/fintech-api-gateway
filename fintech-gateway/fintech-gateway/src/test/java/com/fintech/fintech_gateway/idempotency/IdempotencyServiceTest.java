package com.fintech.fintech_gateway.idempotency;

import com.fintech.fintech_gateway.idempotency.IdempotencyService.Acquisition;
import com.fintech.fintech_gateway.idempotency.IdempotencyService.StoredResponse;
import com.fintech.fintech_gateway.support.TestProperties;
import com.fintech.fintech_gateway.web.GatewayRequestException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IdempotencyServiceTest {

    private static final String KEY = "idem:tenant-a:key-12345678";

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOperations;
    private ObjectMapper objectMapper;
    private IdempotencyService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        objectMapper = new ObjectMapper();
        service = new IdempotencyService(redisTemplate, objectMapper, TestProperties.defaults());
    }

    @Test
    void firstRequestClaimsTheKey() {
        when(valueOperations.setIfAbsent(eq(KEY), anyString(), eq(Duration.ofHours(24))))
                .thenReturn(true);

        var result = service.acquire("tenant-a", "key-12345678");

        assertEquals(Acquisition.ACQUIRED, result.state());
    }

    @Test
    void completedRequestReturnsStoredResponse() throws Exception {
        byte[] body = new byte[]{0x1f, (byte) 0x8b, 0x08, 0x00, (byte) 0xff};
        StoredResponse stored = new StoredResponse(201, "application/json", "gzip", body);
        when(valueOperations.setIfAbsent(eq(KEY), anyString(), eq(Duration.ofHours(24))))
                .thenReturn(false);
        when(valueOperations.get(KEY)).thenReturn(objectMapper.writeValueAsString(stored));

        var result = service.acquire("tenant-a", "key-12345678");

        assertEquals(Acquisition.COMPLETED, result.state());
        assertEquals(201, result.response().status());
        assertEquals("application/json", result.response().contentType());
        assertEquals("gzip", result.response().contentEncoding());
        assertArrayEquals(body, result.response().body(),
                "binary and compressed bodies must survive the Redis round trip unchanged");
    }

    @Test
    void concurrentRequestIsInProgress() {
        when(valueOperations.setIfAbsent(eq(KEY), anyString(), eq(Duration.ofHours(24))))
                .thenReturn(false);
        when(valueOperations.get(KEY)).thenReturn("IN_PROGRESS");

        var result = service.acquire("tenant-a", "key-12345678");

        assertEquals(Acquisition.IN_PROGRESS, result.state());
    }

    @Test
    void expiredRaceRetriesTheClaimOnce() {
        when(valueOperations.setIfAbsent(eq(KEY), anyString(), eq(Duration.ofHours(24))))
                .thenReturn(false, true);
        when(valueOperations.get(KEY)).thenReturn(null);

        var result = service.acquire("tenant-a", "key-12345678");

        assertEquals(Acquisition.ACQUIRED, result.state());
    }

    @Test
    @SuppressWarnings("unchecked")
    void completeUsesAtomicCompareAndSet() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(1L);

        boolean completed = service.complete("tenant-a", "key-12345678", "owner-token",
                new StoredResponse(200, "application/json", null,
                        "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8)));

        assertTrue(completed);
    }

    @Test
    @SuppressWarnings("unchecked")
    void completePassesTheClaimOwnerTokenToTheScript() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(1L);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);

        service.complete("tenant-a", "key-12345678", "owner-token",
                new StoredResponse(200, "application/json", null, new byte[]{1}));

        verify(redisTemplate).execute(any(RedisScript.class), anyList(), args.capture());
        assertEquals("IN_PROGRESS:owner-token", args.getValue()[0]);
    }

    @Test
    @SuppressWarnings("unchecked")
    void completeReturnsFalseWhenOwnershipWasLost() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(0L);

        assertFalse(service.complete("tenant-a", "key-12345678", "owner-token",
                new StoredResponse(200, "application/json", null,
                        "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8))));
    }

    @Test
    @SuppressWarnings("unchecked")
    void releaseOnlyDeletesOwnedKey() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(1L);

        assertTrue(service.release("tenant-a", "key-12345678", "owner-token"));
    }

    @Test
    void corruptStoredResponseFailsClosed() {
        when(valueOperations.setIfAbsent(eq(KEY), anyString(), eq(Duration.ofHours(24))))
                .thenReturn(false);
        when(valueOperations.get(KEY)).thenReturn("{not-json");

        GatewayRequestException exception = assertThrows(GatewayRequestException.class,
                () -> service.acquire("tenant-a", "key-12345678"));

        assertEquals(409, exception.getStatus().value());
        assertEquals("idempotency_state_corrupt", exception.getCode());
    }

    @Test
    void missingKeyIsRejected() {
        GatewayRequestException exception = assertThrows(GatewayRequestException.class,
                () -> service.validateKey(null));
        assertEquals(400, exception.getStatus().value());
        assertEquals("idempotency_key_required", exception.getCode());
    }

    @Test
    void oversizedKeyIsRejected() {
        GatewayRequestException exception = assertThrows(GatewayRequestException.class,
                () -> service.validateKey("a".repeat(256)));
        assertEquals("invalid_idempotency_key", exception.getCode());
    }

    @Test
    void unsafeCharactersAreRejected() {
        GatewayRequestException exception = assertThrows(GatewayRequestException.class,
                () -> service.validateKey("key with spaces"));
        assertEquals("invalid_idempotency_key", exception.getCode());
    }

    @Test
    void validKeyPassesValidation() {
        service.validateKey("key-12345678");
    }

    @Test
    void keysAreScopedPerTenant() {
        when(valueOperations.setIfAbsent(eq("idem:tenant-b:key-12345678"),
                anyString(), eq(Duration.ofHours(24)))).thenReturn(true);

        service.acquire("tenant-b", "key-12345678");

        verify(valueOperations).setIfAbsent(eq("idem:tenant-b:key-12345678"),
                anyString(), eq(Duration.ofHours(24)));
    }
}
