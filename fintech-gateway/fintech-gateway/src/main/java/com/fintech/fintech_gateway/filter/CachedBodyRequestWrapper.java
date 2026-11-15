package com.fintech.fintech_gateway.filter;

import com.fintech.fintech_gateway.web.GatewayRequestException;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import org.springframework.http.HttpStatus;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * Buffers a request body once so the WAF, the risk engine and the proxy can
 * all inspect/forward the same bytes. Reading a servlet input stream twice is
 * not supported, and the previous implementation silently forwarded an empty
 * body after the filter had consumed it.
 */
public class CachedBodyRequestWrapper extends HttpServletRequestWrapper {

    public static final String BODY_ATTRIBUTE = CachedBodyRequestWrapper.class.getName() + ".body";

    private final byte[] cachedBody;

    public CachedBodyRequestWrapper(HttpServletRequest request, byte[] body) {
        super(request);
        this.cachedBody = body != null ? body.clone() : new byte[0];
        request.setAttribute(BODY_ATTRIBUTE, this.cachedBody);
    }

    public byte[] getCachedBody() {
        return cachedBody.clone();
    }

    public String getBodyAsString() {
        return new String(cachedBody, resolveCharset());
    }

    @Override
    public ServletInputStream getInputStream() {
        ByteArrayInputStream input = new ByteArrayInputStream(cachedBody);
        return new ServletInputStream() {
            @Override
            public boolean isFinished() {
                return input.available() == 0;
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setReadListener(ReadListener readListener) {
                // Blocking servlet stack; listeners are not used.
            }

            @Override
            public int read() {
                return input.read();
            }

            @Override
            public int read(byte[] buffer, int offset, int length) {
                return input.read(buffer, offset, length);
            }
        };
    }

    @Override
    public BufferedReader getReader() {
        return new BufferedReader(new InputStreamReader(getInputStream(), resolveCharset()));
    }

    @Override
    public int getContentLength() {
        return cachedBody.length;
    }

    @Override
    public long getContentLengthLong() {
        return cachedBody.length;
    }

    private Charset resolveCharset() {
        String encoding = getCharacterEncoding();
        if (encoding == null || encoding.isBlank()) {
            return StandardCharsets.UTF_8;
        }
        try {
            return Charset.forName(encoding);
        } catch (RuntimeException unsupported) {
            return StandardCharsets.UTF_8;
        }
    }

    /**
     * Reads the request body with a hard byte ceiling. Checking
     * Content-Length first avoids reading a body that is already known to be
     * oversized; the streaming check covers chunked requests that omit it.
     */
    public static byte[] readBounded(HttpServletRequest request, int maxBytes) throws IOException {
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("maxBytes must be positive");
        }
        long declaredLength = request.getContentLengthLong();
        if (declaredLength > maxBytes) {
            throw payloadTooLarge();
        }

        int initialCapacity = declaredLength > 0
                ? (int) Math.min(declaredLength, maxBytes)
                : 1024;
        ByteArrayOutputStream output = new ByteArrayOutputStream(initialCapacity);
        byte[] buffer = new byte[8192];
        int total = 0;
        try (ServletInputStream input = request.getInputStream()) {
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > maxBytes) {
                    throw payloadTooLarge();
                }
                output.write(buffer, 0, read);
            }
        }
        return output.toByteArray();
    }

    /**
     * Returns the already-buffered body when the pipeline wrapped the
     * request, otherwise reads it once with a defensive ceiling.
     */
    public static byte[] getBody(HttpServletRequest request) throws IOException {
        Object cached = request.getAttribute(BODY_ATTRIBUTE);
        if (cached instanceof byte[] bytes) {
            return bytes.clone();
        }
        return readBounded(request, Integer.MAX_VALUE);
    }

    private static GatewayRequestException payloadTooLarge() {
        return new GatewayRequestException(HttpStatus.PAYLOAD_TOO_LARGE,
                "payload_too_large", "Request payload exceeds the configured limit.");
    }
}
