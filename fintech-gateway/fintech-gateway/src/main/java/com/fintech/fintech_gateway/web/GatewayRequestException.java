package com.fintech.fintech_gateway.web;

import org.springframework.http.HttpStatus;

/**
 * Expected, client-facing failure. Anything else is treated as an internal
 * fault and fails closed with a 503 rather than leaking implementation detail.
 */
public class GatewayRequestException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public GatewayRequestException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus getStatus() { return status; }
    public String getCode() { return code; }
}
