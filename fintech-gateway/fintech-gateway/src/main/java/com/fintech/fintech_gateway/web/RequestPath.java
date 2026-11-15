package com.fintech.fintech_gateway.web;

import org.springframework.stereotype.Component;

import java.util.Set;

@Component
public class RequestPath {

    private static final Set<String> INTERNAL_PREFIXES = Set.of(
            "/health", "/ping", "/feedback", "/compliance", "/actuator",
            "/swagger-ui", "/v3/api-docs", "/favicon.ico", "/error");

    public boolean isInternal(String path) {
        if (path == null || path.isEmpty()) {
            return true;
        }
        for (String prefix : INTERNAL_PREFIXES) {
            if (path.equals(prefix) || path.startsWith(prefix + "/")) {
                return true;
            }
        }
        return false;
    }
}
