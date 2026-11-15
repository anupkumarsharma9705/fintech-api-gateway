package com.fintech.fintech_gateway.filter;

import com.fintech.fintech_gateway.web.GatewayRequestException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CachedBodyRequestWrapperTest {

    @Test
    void constructorCachesBodyAndPublishesAttribute() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        byte[] body = "{\"amount\":500}".getBytes(StandardCharsets.UTF_8);

        CachedBodyRequestWrapper wrapper = new CachedBodyRequestWrapper(request, body);

        assertArrayEquals(body, wrapper.getCachedBody());
        assertArrayEquals(body, (byte[]) request.getAttribute(CachedBodyRequestWrapper.BODY_ATTRIBUTE));
        assertEquals(body.length, wrapper.getContentLength());
        assertEquals(body.length, wrapper.getContentLengthLong());
    }

    @Test
    void inputStreamCanBeReadRepeatedly() throws IOException {
        CachedBodyRequestWrapper wrapper = new CachedBodyRequestWrapper(
                new MockHttpServletRequest(), "payload".getBytes(StandardCharsets.UTF_8));

        byte[] first = wrapper.getInputStream().readAllBytes();
        byte[] second = wrapper.getInputStream().readAllBytes();

        assertEquals("payload", new String(first, StandardCharsets.UTF_8));
        assertArrayEquals(first, second);
    }

    @Test
    void readerHonoursCharacterEncoding() throws IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCharacterEncoding("UTF-8");
        CachedBodyRequestWrapper wrapper = new CachedBodyRequestWrapper(
                request, "amount=₹500".getBytes(StandardCharsets.UTF_8));

        assertEquals("amount=₹500", wrapper.getBodyAsString());
        assertEquals("amount=₹500", wrapper.getReader().readLine());
    }

    @Test
    void nullBodyBecomesEmptyBody() {
        CachedBodyRequestWrapper wrapper = new CachedBodyRequestWrapper(new MockHttpServletRequest(), null);

        assertEquals(0, wrapper.getContentLength());
        assertEquals("", wrapper.getBodyAsString());
    }

    @Test
    void readBoundedRejectsDeclaredOversizedBody() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setContent("0123456789".getBytes(StandardCharsets.UTF_8));

        GatewayRequestException exception = assertThrows(GatewayRequestException.class,
                () -> CachedBodyRequestWrapper.readBounded(request, 5));

        assertEquals(413, exception.getStatus().value());
        assertEquals("payload_too_large", exception.getCode());
    }

    @Test
    void readBoundedRejectsStreamedBodyWithoutContentLength() {
        MockHttpServletRequest request = new MockHttpServletRequest() {
            @Override
            public long getContentLengthLong() {
                return -1L;
            }
        };
        request.setContent("0123456789".getBytes(StandardCharsets.UTF_8));

        assertThrows(GatewayRequestException.class,
                () -> CachedBodyRequestWrapper.readBounded(request, 5));
    }

    @Test
    void readBoundedAcceptsBodyAtTheLimit() throws IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setContent("12345".getBytes(StandardCharsets.UTF_8));

        assertEquals("12345", new String(
                CachedBodyRequestWrapper.readBounded(request, 5), StandardCharsets.UTF_8));
    }

    @Test
    void readBoundedRequiresPositiveLimit() {
        assertThrows(IllegalArgumentException.class,
                () -> CachedBodyRequestWrapper.readBounded(new MockHttpServletRequest(), 0));
    }

    @Test
    void getBodyReturnsBufferedBytesWithoutReReadingTheStream() throws IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        byte[] cached = "cached".getBytes(StandardCharsets.UTF_8);
        request.setAttribute(CachedBodyRequestWrapper.BODY_ATTRIBUTE, cached);

        assertArrayEquals(cached, CachedBodyRequestWrapper.getBody(request));
    }

    @Test
    void getBodyReadsUnbufferedRequest() throws IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setContent("fresh".getBytes(StandardCharsets.UTF_8));

        assertEquals("fresh", new String(
                CachedBodyRequestWrapper.getBody(request), StandardCharsets.UTF_8));
    }
}
