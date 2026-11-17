package com.fintech.fintech_gateway.feedback;

import com.fintech.fintech_gateway.config.GatewayProperties;
import com.fintech.fintech_gateway.trust.TrustTierService;
import com.fintech.fintech_gateway.web.GatewayRequestException;
import com.fintech.fintech_gateway.web.IpAddressValidator;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.regex.Pattern;

/**
 * Operator feedback. Trust state is tenant-scoped, so a false-positive reset
 * must name the tenant: an IP-only reset would clear the wrong customer's
 * penalties, or silently do nothing for any tenant other than {@code default}.
 */
@Service
public class FeedbackService {

    private static final int MAX_REASON_LENGTH = 400;

    private final TrustTierService trustTierService;
    private final Pattern tenantPattern;

    public FeedbackService(TrustTierService trustTierService, GatewayProperties properties) {
        this.trustTierService = trustTierService;
        this.tenantPattern = Pattern.compile(properties.getTenant().getPattern());
    }

    public void handleFalsePositive(String tenantId, String ip, String reason) {
        if (tenantId == null || !tenantPattern.matcher(tenantId).matches()) {
            throw new GatewayRequestException(HttpStatus.BAD_REQUEST,
                    "invalid_tenant", "A valid tenant id is required.");
        }
        IpAddressValidator.requireValid(ip);
        String sanitizedReason = sanitizeReason(reason);
        trustTierService.resetToMedium(tenantId, ip, sanitizedReason);
    }

    private String sanitizeReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return "False positive reported by ops team";
        }
        String cleaned = reason.replaceAll("[\\r\\n\\t\\u0000-\\u001F\\u007F]", " ").trim();
        return cleaned.length() > MAX_REASON_LENGTH
                ? cleaned.substring(0, MAX_REASON_LENGTH)
                : cleaned;
    }
}
