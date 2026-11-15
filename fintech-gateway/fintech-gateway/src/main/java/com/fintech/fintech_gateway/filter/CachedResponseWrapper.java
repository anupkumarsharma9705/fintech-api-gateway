package com.fintech.fintech_gateway.filter;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * Captures the response so an idempotent request can be replayed byte for
 * byte. The wrapper never commits the underlying response: flushBuffer()
 * only flushes the in-memory writer, and copyBodyToResponse() is the single
 * place that writes to the real client.
 */
public class CachedResponseWrapper extends HttpServletResponseWrapper {

    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream(1024);
    private ServletOutputStream outputStream;
    private PrintWriter writer;

    public CachedResponseWrapper(HttpServletResponse response) {
        super(response);
    }

    @Override
    public ServletOutputStream getOutputStream() {
        if (writer != null) {
            throw new IllegalStateException("getWriter() has already been called on this response");
        }
        if (outputStream == null) {
            outputStream = new ServletOutputStream() {
                @Override
                public void write(int value) {
                    buffer.write(value);
                }

                @Override
                public void write(byte[] bytes, int offset, int length) {
                    buffer.write(bytes, offset, length);
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setWriteListener(WriteListener listener) {
                    // Blocking servlet stack; listeners are not used.
                }
            };
        }
        return outputStream;
    }

    @Override
    public PrintWriter getWriter() {
        if (outputStream != null) {
            throw new IllegalStateException("getOutputStream() has already been called on this response");
        }
        if (writer == null) {
            writer = new PrintWriter(new OutputStreamWriter(buffer, resolveCharset()), false);
        }
        return writer;
    }

    @Override
    public void flushBuffer() {
        flushWriter();
    }

    @Override
    public void resetBuffer() {
        super.resetBuffer();
        buffer.reset();
        // Discard characters still buffered inside the PrintWriter. Without
        // this, a later flush would resurrect output that was meant to be
        // dropped by resetBuffer().
        if (writer != null) {
            writer = new PrintWriter(new OutputStreamWriter(buffer, resolveCharset()), false);
        }
    }

    @Override
    public void reset() {
        super.reset();
        buffer.reset();
        writer = null;
        outputStream = null;
    }

    public byte[] getCapturedBody() {
        flushWriter();
        return buffer.toByteArray();
    }

    public String getCapturedBodyAsString() {
        return new String(getCapturedBody(), resolveCharset());
    }

    /**
     * Writes the captured bytes to the real response exactly once. Callers
     * must not have written to the underlying response directly.
     */
    public void copyBodyToResponse() throws IOException {
        byte[] body = getCapturedBody();
        if (isCommitted()) {
            return;
        }
        int status = getStatus();
        if (status == HttpServletResponse.SC_NO_CONTENT || status == HttpServletResponse.SC_NOT_MODIFIED) {
            return;
        }
        if (body.length > 0) {
            super.setContentLength(body.length);
            ServletOutputStream target = super.getOutputStream();
            target.write(body);
            target.flush();
        }
    }

    private void flushWriter() {
        if (writer != null) {
            writer.flush();
        }
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
}
