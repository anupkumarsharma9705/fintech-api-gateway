package com.fintech.fintech_gateway.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.util.StringUtils;

/**
 * A browser CORS preflight is an {@code OPTIONS} request carrying both
 * {@code Origin} and {@code Access-Control-Request-Method}. Browsers never
 * attach an {@code Authorization} header or custom credentials to it, so the
 * gateway's admission filters must let Spring MVC answer it. Every other
 * {@code OPTIONS} request is treated as a normal request and authenticated.
 */
public final class CorsPreflight {

    private CorsPreflight() {
    }

    public static boolean isPreflight(HttpServletRequest request) {
        return "OPTIONS".equalsIgnoreCase(request.getMethod())
                && StringUtils.hasText(request.getHeader("Origin"))
                && StringUtils.hasText(request.getHeader("Access-Control-Request-Method"));
    }
}
