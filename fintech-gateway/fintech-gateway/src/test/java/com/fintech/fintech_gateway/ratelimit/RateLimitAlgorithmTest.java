package com.fintech.fintech_gateway.ratelimit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RateLimitAlgorithmTest {

    @Test
    void blankConfigurationDefaultsToGcra() {
        assertEquals(RateLimitAlgorithm.GCRA, RateLimitAlgorithm.from(null));
        assertEquals(RateLimitAlgorithm.GCRA, RateLimitAlgorithm.from(""));
        assertEquals(RateLimitAlgorithm.GCRA, RateLimitAlgorithm.from("   "));
    }

    @Test
    void gcraIsCaseInsensitive() {
        assertEquals(RateLimitAlgorithm.GCRA, RateLimitAlgorithm.from("gcra"));
        assertEquals(RateLimitAlgorithm.GCRA, RateLimitAlgorithm.from("GCRA"));
        assertEquals(RateLimitAlgorithm.GCRA, RateLimitAlgorithm.from(" Gcra "));
    }

    @Test
    void sortedSetAcceptsDocumentedAndLegacySpellings() {
        assertEquals(RateLimitAlgorithm.SORTED_SET, RateLimitAlgorithm.from("sorted-set"));
        assertEquals(RateLimitAlgorithm.SORTED_SET, RateLimitAlgorithm.from("sorted_set"));
        assertEquals(RateLimitAlgorithm.SORTED_SET, RateLimitAlgorithm.from("sortedset"));
        assertEquals(RateLimitAlgorithm.SORTED_SET, RateLimitAlgorithm.from("zset"));
    }

    @Test
    void unknownAlgorithmFailsFastWithAnActionableMessage() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> RateLimitAlgorithm.from("token-bucket"));

        assertTrue(error.getMessage().contains("gateway.rate-limit.algorithm"));
        assertTrue(error.getMessage().contains("token-bucket"));
    }
}
