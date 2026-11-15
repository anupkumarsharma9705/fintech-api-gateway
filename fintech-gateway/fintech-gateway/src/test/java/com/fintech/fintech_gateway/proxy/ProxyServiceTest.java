package com.fintech.fintech_gateway.proxy;

import com.fintech.fintech_gateway.metrics.GatewayMetrics;
import com.fintech.fintech_gateway.support.TestProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class ProxyServiceTest {

    private MockRestServiceServer server;
    private SimpleMeterRegistry registry;
    private ProxyService service;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://backend");
        server = MockRestServiceServer.bindTo(builder).build();
        registry = new SimpleMeterRegistry();
        service = new ProxyService(builder.build(), new GatewayMetrics(registry),
                TestProperties.defaults());
    }

    @Test
    void forwardsMethodPathHeadersAndBody() {
        server.expect(requestTo("http://backend/api/payments?currency=INR"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer token"))
                .andExpect(header("Idempotency-Key", "key-1"))
                .andExpect(content().json("{\"amount\":500}"))
                .andRespond(withStatus(HttpStatus.CREATED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"id\":\"pay_1\"}"));

        ResponseEntity<byte[]> response = service.forward(
                "/api/payments?currency=INR",
                HttpMethod.POST,
                "{\"amount\":500}".getBytes(StandardCharsets.UTF_8),
                Map.of("Authorization", "Bearer token",
                        "Idempotency-Key", "key-1",
                        "Content-Type", "application/json"));

        server.verify();
        assertEquals(201, response.getStatusCode().value());
        assertEquals("{\"id\":\"pay_1\"}", new String(response.getBody(), StandardCharsets.UTF_8));
        assertEquals(MediaType.APPLICATION_JSON, response.getHeaders().getContentType());
    }

    @Test
    void upstreamClientErrorsArePreserved() {
        server.expect(requestTo("http://backend/api/payments"))
                .andRespond(withStatus(HttpStatus.CONFLICT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":\"duplicate_payment\"}"));

        ResponseEntity<byte[]> response = service.forward(
                "/api/payments", HttpMethod.POST,
                "{\"amount\":500}".getBytes(StandardCharsets.UTF_8),
                Map.of("Content-Type", "application/json"));

        assertEquals(409, response.getStatusCode().value());
        assertEquals("{\"error\":\"duplicate_payment\"}",
                new String(response.getBody(), StandardCharsets.UTF_8));
    }

    @Test
    void upstreamServerErrorsArePreserved() {
        server.expect(requestTo("http://backend/api/account/balance"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body("upstream exploded"));

        ResponseEntity<byte[]> response = service.forward(
                "/api/account/balance", HttpMethod.GET, null, Map.of());

        assertEquals(500, response.getStatusCode().value());
        assertEquals("upstream exploded",
                new String(response.getBody(), StandardCharsets.UTF_8));
    }

    @Test
    void onlySafeResponseHeadersAreForwarded() {
        server.expect(requestTo("http://backend/api/account/balance"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON)
                        .header("Cache-Control", "no-store")
                        .header("Content-Encoding", "gzip")
                        .header("X-Request-Id", "upstream-id")
                        .header("X-Internal-Secret", "do-not-leak"));

        ResponseEntity<byte[]> response = service.forward(
                "/api/account/balance", HttpMethod.GET, null, Map.of());

        assertEquals("no-store", response.getHeaders().getFirst("Cache-Control"));
        assertEquals("gzip", response.getHeaders().getFirst("Content-Encoding"));
        assertNull(response.getHeaders().getFirst("X-Request-Id"),
                "the gateway's correlation id must not be overwritten by the upstream");
        assertNull(response.getHeaders().getFirst("X-Internal-Secret"));
    }

    @Test
    void getRequestsDoNotSendABody() {
        server.expect(requestTo("http://backend/api/account/balance"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        service.forward("/api/account/balance", HttpMethod.GET,
                "ignored".getBytes(StandardCharsets.UTF_8), Map.of());

        server.verify();
    }

    @Test
    void connectionFailureMapsTo503() {
        RestClient failingClient = RestClient.builder()
                .baseUrl("http://backend")
                .requestFactory((uri, httpMethod) -> {
                    throw new IOException("connection refused");
                })
                .build();
        ProxyService failingService = new ProxyService(failingClient, new GatewayMetrics(registry),
                TestProperties.defaults());

        ResponseEntity<byte[]> response = failingService.forward(
                "/api/account/balance", HttpMethod.GET, null, Map.of());

        assertEquals(503, response.getStatusCode().value());
        String body = new String(response.getBody(), StandardCharsets.UTF_8);
        assertTrue(body.contains("upstream_unavailable"));
        assertEquals(1.0, registry.counter("gateway.upstream.errors").count());
    }

    @Test
    void oversizedUpstreamResponseIsRejectedWith502() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://backend");
        MockRestServiceServer boundedServer = MockRestServiceServer.bindTo(builder).build();
        boundedServer.expect(requestTo("http://backend/api/account/balance"))
                .andRespond(withSuccess("x".repeat(17), MediaType.TEXT_PLAIN));

        var properties = TestProperties.defaults();
        properties.getProxy().setMaxResponseBytes(16);
        ProxyService bounded = new ProxyService(
                builder.build(), new GatewayMetrics(registry), properties);

        ResponseEntity<byte[]> response = bounded.forward(
                "/api/account/balance", HttpMethod.GET, null, Map.of());

        assertEquals(502, response.getStatusCode().value());
        assertTrue(new String(response.getBody(), StandardCharsets.UTF_8)
                .contains("upstream_response_too_large"));
        assertEquals(1.0, registry.counter("gateway.upstream.errors").count());
        boundedServer.verify();
    }

    @Test
    void preservesEncodedPathAndQuery() {
        server.expect(requestTo("http://backend/api/payments?note=a%20b&path=%2Ftmp"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        service.forward("/api/payments?note=a%20b&path=%2Ftmp",
                HttpMethod.GET, null, Map.of());

        server.verify();
    }

    @Test
    void malformedTargetReturns400() {
        ResponseEntity<byte[]> response = service.forward(
                "/api/account/balance?q=%zz", HttpMethod.GET, null, Map.of());

        assertEquals(400, response.getStatusCode().value());
        assertTrue(new String(response.getBody(), StandardCharsets.UTF_8)
                .contains("invalid_target"));
    }

    @Test
    void responseLimitMustBePositive() {
        var properties = TestProperties.defaults();
        properties.getProxy().setMaxResponseBytes(0);

        assertThrows(IllegalArgumentException.class, () -> new ProxyService(
                RestClient.builder().baseUrl("http://backend").build(),
                new GatewayMetrics(registry), properties));
    }
}
