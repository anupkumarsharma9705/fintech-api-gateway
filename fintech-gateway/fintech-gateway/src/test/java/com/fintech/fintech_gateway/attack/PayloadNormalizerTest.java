package com.fintech.fintech_gateway.attack;

import com.fintech.fintech_gateway.support.TestProperties;
import com.fintech.fintech_gateway.web.GatewayRequestException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PayloadNormalizerTest {

    private PayloadNormalizer normalizer;

    @BeforeEach
    void setUp() {
        normalizer = new PayloadNormalizer(TestProperties.defaults());
    }

    @Test
    void decodesPercentEncoding() {
        assertEquals("' OR '1'='1",
                normalizer.normalize("%27%20OR%20%271%27%3D%271"));
    }

    @Test
    void decodesDoubleEncoding() {
        assertEquals("<script>", normalizer.normalize("%253Cscript%253E"));
    }

    @Test
    void decodesHtmlEntities() {
        assertEquals("<script>alert(1)</script>",
                normalizer.normalize("&lt;script&gt;alert(1)&lt;/script&gt;"));
    }

    @Test
    void normalizesUnicodeCompatibilityCharacters() {
        assertEquals("<script>", normalizer.normalize("\uFF1Cscript\uFF1E"));
    }

    @Test
    void replacesControlCharacters() {
        assertEquals("a b", normalizer.normalize("a\u0000b"));
    }

    @Test
    void preservesLiteralPlus() {
        assertEquals("a+b", normalizer.normalize("a+b"));
    }

    @Test
    void malformedEncodingDoesNotCrash() {
        assertEquals("%zz", normalizer.normalize("%zz"));
    }

    @Test
    void oversizedPayloadIsRejectedWith413() {
        // PayloadNormalizer enforces max(configuredBodyBytes, 8192).
        String oversized = "a".repeat(9000);
        GatewayRequestException exception = assertThrows(GatewayRequestException.class,
                () -> normalizer.normalize(oversized));
        assertEquals(413, exception.getStatus().value());
        assertEquals("payload_too_large", exception.getCode());
    }
}
