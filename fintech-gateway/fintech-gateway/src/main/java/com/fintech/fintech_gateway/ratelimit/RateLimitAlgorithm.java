package com.fintech.fintech_gateway.ratelimit;

import java.util.Locale;

/**
 * Rate-limit storage algorithm.
 *
 * <p>{@code gcra} is the default: one number per bucket, O(1) memory and CPU,
 * with the same "limit requests per window" contract as the previous sorted
 * set. {@code sorted-set} keeps the exact per-event sliding window available
 * for deployments that need to inspect or replay individual slots.</p>
 */
public enum RateLimitAlgorithm {
    GCRA,
    SORTED_SET;

    public static RateLimitAlgorithm from(String configured) {
        if (configured == null || configured.isBlank()) {
            return GCRA;
        }
        String normalized = configured.trim()
                .toLowerCase(Locale.ROOT)
                .replace('_', '-');
        return switch (normalized) {
            case "gcra" -> GCRA;
            case "sorted-set", "sortedset", "zset" -> SORTED_SET;
            default -> throw new IllegalArgumentException(
                    "gateway.rate-limit.algorithm must be 'gcra' or 'sorted-set', got '"
                            + configured + "'");
        };
    }
}
