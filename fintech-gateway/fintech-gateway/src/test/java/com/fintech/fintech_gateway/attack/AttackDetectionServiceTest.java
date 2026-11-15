package com.fintech.fintech_gateway.attack;

import com.fintech.fintech_gateway.support.TestProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AttackDetectionServiceTest {

    private AttackDetectionService service;

    @BeforeEach
    void setUp() {
        service = new AttackDetectionService(new PayloadNormalizer(TestProperties.defaults()));
    }

    @Test
    void cleanPayloadIsNotFlagged() {
        var result = service.inspect(
                "{\"amount\":5000,\"currency\":\"INR\"}", "/api/payments");
        assertFalse(result.isThreatDetected());
    }

    @Test
    void classicSqlInjectionIsDetected() {
        var result = service.inspect(
                "{\"accountId\":\"ACC123' OR '1'='1\"}", "/api/payments");
        assertTrue(result.isThreatDetected());
        assertEquals("SQL_INJECTION", result.getThreatType());
    }

    @Test
    void unionSelectIsDetected() {
        var result = service.inspect(
                "{\"q\":\"x UNION ALL SELECT password FROM users\"}", "/api/account/profile");
        assertTrue(result.isThreatDetected());
        assertEquals("SQL_INJECTION", result.getThreatType());
    }

    @Test
    void xssIsDetected() {
        var result = service.inspect(
                "{\"name\":\"<script>alert(1)</script>\"}", "/api/account/profile");
        assertTrue(result.isThreatDetected());
        assertEquals("XSS", result.getThreatType());
    }

    @Test
    void encodedXssIsDetectedAfterNormalization() {
        var result = service.inspect(
                "{\"name\":\"%3Cscript%3Ealert(1)%3C/script%3E\"}", "/api/account/profile");
        assertTrue(result.isThreatDetected());
        assertEquals("XSS", result.getThreatType());
    }

    @Test
    void pathTraversalIsDetected() {
        var result = service.inspect("", "/api/files/../../etc/passwd");
        assertTrue(result.isThreatDetected());
        assertEquals("PATH_TRAVERSAL", result.getThreatType());
    }

    @Test
    void commandInjectionIsDetected() {
        var result = service.inspect(
                "{\"file\":\"report; cat /etc/passwd\"}", "/api/reports");
        assertTrue(result.isThreatDetected());
        assertEquals("COMMAND_INJECTION", result.getThreatType());
    }

    @Test
    void queryStringIsInspected() {
        var result = service.inspect("", "/api/account/balance", "q=' OR 1=1--");
        assertTrue(result.isThreatDetected());
        assertEquals("SQL_INJECTION", result.getThreatType());
    }

    @Test
    void benignKeywordIsNotFlagged() {
        var result = service.inspect(
                "{\"description\":\"Please select the account and confirm the amount\"}",
                "/api/payments");
        assertFalse(result.isThreatDetected());
    }

    @Test
    void emptyInputIsClean() {
        assertFalse(service.inspect(null, null, null).isThreatDetected());
    }
}
