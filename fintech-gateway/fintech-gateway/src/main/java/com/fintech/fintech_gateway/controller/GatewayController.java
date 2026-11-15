package com.fintech.fintech_gateway.controller;

import com.fintech.fintech_gateway.config.GatewayProperties;
import com.fintech.fintech_gateway.auth.AuthenticatedClient;
import com.fintech.fintech_gateway.auth.ClientAuthenticator;
import com.fintech.fintech_gateway.filter.CachedBodyRequestWrapper;
import com.fintech.fintech_gateway.proxy.ProxyService;
import com.fintech.fintech_gateway.web.ClientIpResolver;
import com.fintech.fintech_gateway.web.RequestIdFilter;
import com.fintech.fintech_gateway.web.RequestMethods;
import com.fintech.fintech_gateway.web.RequestPath;
import com.fintech.fintech_gateway.web.TenantResolver;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Catch-all proxy endpoint. All admission, WAF and idempotency logic lives in
 * the filter pipeline; this controller only translates the servlet request
 * into an upstream call and returns the upstream response unchanged.
 */
@RestController
@Hidden
public class GatewayController {

    /**
     * Methods the gateway is willing to proxy live in {@link RequestMethods}
     * so the admission filter and this controller share one allowlist.
     */

    private final ProxyService proxyService;
    private final RequestPath requestPath;
    private final GatewayProperties properties;
    private final ClientIpResolver clientIpResolver;
    private final TenantResolver tenantResolver;

    public GatewayController(ProxyService proxyService,
                             RequestPath requestPath,
                             GatewayProperties properties,
                             ClientIpResolver clientIpResolver,
                             TenantResolver tenantResolver) {
        this.proxyService = proxyService;
        this.requestPath = requestPath;
        this.properties = properties;
        this.clientIpResolver = clientIpResolver;
        this.tenantResolver = tenantResolver;
    }

    @RequestMapping("/**")
    public ResponseEntity<byte[]> handle(HttpServletRequest request) throws IOException {
        String path = request.getRequestURI();
        if (requestPath.isInternal(path)) {
            return ResponseEntity.notFound().build();
        }

        if (!RequestMethods.isAllowed(request.getMethod())) {
            return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).build();
        }
        HttpMethod method = HttpMethod.valueOf(request.getMethod());

        String query = request.getQueryString();
        String target = query == null ? path : path + "?" + query;

        byte[] body = CachedBodyRequestWrapper.getBody(request);

        Map<String, String> headers = new LinkedHashMap<>();
        for (String name : properties.getProxy().getForwardHeaders()) {
            String value = request.getHeader(name);
            if (StringUtils.hasText(value)) {
                headers.put(name, value);
            }
        }
        headers.put("X-Request-Id", RequestIdFilter.get(request));
        headers.put("X-Tenant-Id", tenantResolver.get(request));
        Object authenticated = request.getAttribute(ClientAuthenticator.ATTRIBUTE);
        if (authenticated instanceof AuthenticatedClient client) {
            headers.put("X-Client-Id", client.clientId());
        }
        headers.put("X-Forwarded-For", clientIpResolver.resolve(request));

        return proxyService.forward(target, method, body, headers);
    }
}
