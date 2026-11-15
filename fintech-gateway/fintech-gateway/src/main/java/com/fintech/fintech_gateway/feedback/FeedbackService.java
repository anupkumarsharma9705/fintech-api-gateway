package com.fintech.fintech_gateway.feedback;

import com.fintech.fintech_gateway.trust.TrustTierService;
import com.fintech.fintech_gateway.web.IpAddressValidator;
import org.springframework.stereotype.Service;

@Service
public class FeedbackService {

    private static final int MAX_REASON_LENGTH = 400;

    private final TrustTierService trustTierService;

    public FeedbackService(TrustTierService trustTierService) {
        this.trustTierService = trustTierService;
    }

    public void handleFalsePositive(String ip, String reason) {
        IpAddressValidator.requireValid(ip);
        String sanitizedReason = sanitizeReason(reason);
        trustTierService.resetToMedium(ip, sanitizedReason);
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
