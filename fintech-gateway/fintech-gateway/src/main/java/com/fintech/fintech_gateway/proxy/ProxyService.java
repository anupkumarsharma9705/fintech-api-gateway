package com.fintech.fintech_gateway.proxy;

import com.fintech.fintech_gateway.config.GatewayProperties;
import com.fintech.fintech_gateway.metrics.GatewayMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Forwards requests to the upstream service and preserves its status code.
 *
 * <p>The previous implementation used {@code retrieve()}, which throws on
 * every 4xx/5xx response and rewrote legitimate upstream errors (for example
 * a 409 duplicate payment) into a generic 503. This version uses
 * {@code exchange()} so the caller sees the upstream's real status and body,
 * while network failures still map to 503.</p>
 */
@Service
public class ProxyService {

    private static final Logger log = LoggerFactory.getLogger(ProxyService.class);

    private static final Set<String> FORWARDED_RESPONSE_HEADERS = Set.of(
            "content-type", "content-encoding", "cache-control", "etag", "last-modified",
            "location", "retry-after");

    private static final byte[] UPSTREAM_UNAVAILABLE_BODY =
            "{\"error\":\"upstream_unavailable\",\"message\":\"The upstream service is unavailable.\"}"
                    .getBytes(StandardCharsets.UTF_8);

    private static final byte[] UPSTREAM_RESPONSE_TOO_LARGE_BODY =
            "{\"error\":\"upstream_response_too_large\",\"message\":\"The upstream response exceeded the gateway limit.\"}"
                    .getBytes(StandardCharsets.UTF_8);

    private static final byte[] INVALID_TARGET_BODY =
            "{\"error\":\"invalid_target\",\"message\":\"The request target is not a valid URI.\"}"
                    .getBytes(StandardCharsets.UTF_8);

    private final RestClient restClient;
    private final GatewayMetrics metrics;
    private final int maxResponseBytes;

    public ProxyService(RestClient restClient, GatewayMetrics metrics,
                        GatewayProperties properties) {
        this.restClient = restClient;
        this.metrics = metrics;
        this.maxResponseBytes = properties.getProxy().getMaxResponseBytes();
        if (maxResponseBytes <= 0) {
            throw new IllegalArgumentException("gateway.proxy.max-response-bytes must be positive");
        }
    }

    public ResponseEntity<byte[]> forward(String pathAndQuery, HttpMethod method,
                                          byte[] body, Map<String, String> headers) {
        URI target;
        try {
            // Parse instead of passing a template string: RestClient's template
            // expansion would re-encode an already-encoded target (%20 -> %2520).
            target = URI.create(pathAndQuery);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(INVALID_TARGET_BODY);
        }

        try {
            RestClient.RequestBodySpec request = restClient.method(method)
                    .uri(target)
                    .headers(targetHeaders -> headers.forEach(targetHeaders::set));

            if (body != null && body.length > 0
                    && method != HttpMethod.GET && method != HttpMethod.HEAD) {
                request = request.body(body);
            }

            return request.exchange((upstreamRequest, upstreamResponse) -> {
                byte[] payload = readBounded(upstreamResponse.getBody(), maxResponseBytes);
                if (payload == null) {
                    log.warn("Upstream response exceeded the {} byte limit", maxResponseBytes);
                    metrics.recordUpstreamError();
                    return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(UPSTREAM_RESPONSE_TOO_LARGE_BODY);
                }
                HttpHeaders responseHeaders = new HttpHeaders();
                upstreamResponse.getHeaders().forEach((name, values) -> {
                    if (FORWARDED_RESPONSE_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                        responseHeaders.put(name, values);
                    }
                });
                return ResponseEntity.status(upstreamResponse.getStatusCode())
                        .headers(responseHeaders)
                        .body(payload);
            });
        } catch (ResourceAccessException e) {
            log.warn("Upstream unavailable: {}", e.getMessage());
            metrics.recordUpstreamError();
            return unavailable();
        } catch (RestClientException e) {
            log.error("Upstream request failed", e);
            metrics.recordUpstreamError();
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"error\":\"bad_gateway\",\"message\":\"The upstream returned an invalid response.\"}"
                            .getBytes(StandardCharsets.UTF_8));
        }
    }

    private ResponseEntity<byte[]> unavailable() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(UPSTREAM_UNAVAILABLE_BODY);
    }

    /**
     * Reads at most {@code maxBytes}. Returns null when the upstream exceeds
     * the limit so the caller can reject it without ever holding the whole
     * response in memory.
     */
    private byte[] readBounded(InputStream input, int maxBytes) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(8192);
        byte[] chunk = new byte[8192];
        int total = 0;
        int read;
        while ((read = input.read(chunk)) != -1) {
            total += read;
            if (total > maxBytes) {
                return null;
            }
            buffer.write(chunk, 0, read);
        }
        return buffer.toByteArray();
    }
}
