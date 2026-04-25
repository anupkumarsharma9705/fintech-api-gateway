package com.fintech.fintech_gateway.controller;

import com.fintech.fintech_gateway.proxy.ProxyService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.io.IOException;
import java.util.stream.Collectors;

@RestController
public class GatewayController {

    private final ProxyService proxyService;

    public GatewayController(ProxyService proxyService) {
        this.proxyService = proxyService;
    }

    @RequestMapping("/**")
    public ResponseEntity<String> handle(HttpServletRequest request)
            throws IOException {

        String path = request.getRequestURI();

        // Never forward internal browser/swagger requests to backend
        if (path.contains("/favicon.ico") ||
                path.contains("/swagger-ui") ||
                path.contains("/v3/api-docs") ||
                path.startsWith("/feedback") ||
                path.startsWith("/health") ||
                path.startsWith("/ping")) {
            return ResponseEntity.ok("{}");
        }

        String query = request.getQueryString();
        if (query != null) path = path + "?" + query;

        String body = request.getReader()
                .lines()
                .collect(Collectors.joining());

        HttpMethod method = HttpMethod.valueOf(request.getMethod());
        return proxyService.forward(path, method, body);
    }
}