package com.fintech.fintech_gateway.web;

import com.fintech.fintech_gateway.config.GatewayProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.regex.Pattern;

/**
 * Resolves the caller IP. X-Forwarded-For is ignored unless the deployment
 * explicitly opts in, because blindly trusting it lets any client spoof an
 * allow-listed address.
 */
@Component
public class ClientIpResolver {

    private static final Pattern SAFE_IP = Pattern.compile("^[0-9a-fA-F:.]{2,64}$");
    private final GatewayProperties properties;

    public ClientIpResolver(GatewayProperties properties) {
        this.properties = properties;
    }

    public String resolve(HttpServletRequest request) {
        if (properties.getSecurity().isTrustProxy()) {
            String forwarded = request.getHeader("X-Forwarded-For");
            if (StringUtils.hasText(forwarded)) {
                String candidate = forwarded.split(",", 2)[0].trim();
                if (SAFE_IP.matcher(candidate).matches() && IpAddressValidator.isValid(candidate)) {
                    return candidate;
                }
            }
        }
        return request.getRemoteAddr();
    }
}
