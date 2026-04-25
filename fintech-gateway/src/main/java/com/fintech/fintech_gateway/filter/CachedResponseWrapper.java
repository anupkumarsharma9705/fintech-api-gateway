package com.fintech.fintech_gateway.filter;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import java.io.*;

public class CachedResponseWrapper extends HttpServletResponseWrapper {

    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    private final PrintWriter writer = new PrintWriter(buffer);

    public CachedResponseWrapper(HttpServletResponse response) {
        super(response);
    }

    @Override
    public PrintWriter getWriter() {
        return writer;
    }

    @Override
    public ServletOutputStream getOutputStream() {
        return new ServletOutputStream() {
            public void write(int b) { buffer.write(b); }
            public boolean isReady() { return true; }
            public void setWriteListener(WriteListener l) {}
        };
    }

    public String getCapturedBody() {
        writer.flush();
        return buffer.toString();
    }

    public void copyBodyToResponse() throws IOException {
        byte[] body = buffer.toByteArray();
        getResponse().getOutputStream().write(body);
    }
}