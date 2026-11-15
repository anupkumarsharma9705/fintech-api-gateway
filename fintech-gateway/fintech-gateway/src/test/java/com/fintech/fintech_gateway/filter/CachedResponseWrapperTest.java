package com.fintech.fintech_gateway.filter;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CachedResponseWrapperTest {

    @Test
    void writerOutputIsCapturedAndCopiedOnce() throws IOException {
        MockHttpServletResponse response = new MockHttpServletResponse();
        CachedResponseWrapper wrapper = new CachedResponseWrapper(response);
        wrapper.setCharacterEncoding("UTF-8");

        wrapper.getWriter().write("{\"ok\":true}");

        assertEquals("{\"ok\":true}", wrapper.getCapturedBodyAsString());
        assertEquals(0, response.getContentAsByteArray().length,
                "captured output must not leak to the real response before copy");

        wrapper.copyBodyToResponse();

        assertEquals("{\"ok\":true}", response.getContentAsString());
    }

    @Test
    void outputStreamOutputIsCaptured() throws IOException {
        MockHttpServletResponse response = new MockHttpServletResponse();
        CachedResponseWrapper wrapper = new CachedResponseWrapper(response);

        wrapper.getOutputStream().write("binary".getBytes(StandardCharsets.UTF_8));
        wrapper.copyBodyToResponse();

        assertEquals("binary", response.getContentAsString());
    }

    @Test
    void writerAndOutputStreamAreMutuallyExclusive() {
        CachedResponseWrapper wrapper = new CachedResponseWrapper(new MockHttpServletResponse());
        wrapper.getWriter();

        assertThrows(IllegalStateException.class, wrapper::getOutputStream);
    }

    @Test
    void outputStreamThenWriterIsRejected() throws IOException {
        CachedResponseWrapper wrapper = new CachedResponseWrapper(new MockHttpServletResponse());
        wrapper.getOutputStream();

        assertThrows(IllegalStateException.class, wrapper::getWriter);
    }

    @Test
    void flushBufferDoesNotCommitTheRealResponse() throws IOException {
        MockHttpServletResponse response = new MockHttpServletResponse();
        CachedResponseWrapper wrapper = new CachedResponseWrapper(response);

        wrapper.getWriter().write("pending");
        wrapper.flushBuffer();

        assertEquals(0, response.getContentAsByteArray().length);
        assertTrue(!response.isCommitted());
    }

    @Test
    void resetBufferClearsCapturedOutput() throws IOException {
        CachedResponseWrapper wrapper = new CachedResponseWrapper(new MockHttpServletResponse());
        wrapper.getWriter().write("discard me");

        wrapper.resetBuffer();

        assertEquals("", wrapper.getCapturedBodyAsString());
    }

    @Test
    void resetClearsCapturedOutputAndStreams() throws IOException {
        CachedResponseWrapper wrapper = new CachedResponseWrapper(new MockHttpServletResponse());
        wrapper.getOutputStream().write("discard me".getBytes(StandardCharsets.UTF_8));

        wrapper.reset();

        assertEquals(0, wrapper.getCapturedBody().length);
        // After reset a fresh writer is allowed again.
        wrapper.getWriter().write("fresh");
        assertEquals("fresh", wrapper.getCapturedBodyAsString());
    }

    @Test
    void noContentResponseDoesNotWriteABody() throws IOException {
        MockHttpServletResponse response = new MockHttpServletResponse();
        CachedResponseWrapper wrapper = new CachedResponseWrapper(response);
        wrapper.setStatus(204);
        wrapper.getWriter().write("should not be sent");

        wrapper.copyBodyToResponse();

        assertEquals(0, response.getContentAsByteArray().length);
    }

    @Test
    void notModifiedResponseDoesNotWriteABody() throws IOException {
        MockHttpServletResponse response = new MockHttpServletResponse();
        CachedResponseWrapper wrapper = new CachedResponseWrapper(response);
        wrapper.setStatus(304);
        wrapper.getWriter().write("should not be sent");

        wrapper.copyBodyToResponse();

        assertEquals(0, response.getContentAsByteArray().length);
    }

    @Test
    void copySetsContentLength() throws IOException {
        MockHttpServletResponse response = new MockHttpServletResponse();
        CachedResponseWrapper wrapper = new CachedResponseWrapper(response);
        wrapper.getWriter().write("12345");

        wrapper.copyBodyToResponse();

        assertEquals(5, response.getContentLength());
    }

    @Test
    void committedResponseIsNotWrittenAgain() throws IOException {
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setCommitted(true);
        CachedResponseWrapper wrapper = new CachedResponseWrapper(response);
        wrapper.getWriter().write("ignored");

        wrapper.copyBodyToResponse();

        assertEquals(0, response.getContentAsByteArray().length);
    }
}
