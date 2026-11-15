package com.fintech.fintech_gateway.web;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IpAddressValidatorTest {

    @Test
    void acceptsValidIpv4Addresses() {
        assertTrue(IpAddressValidator.isValid("0.0.0.0"));
        assertTrue(IpAddressValidator.isValid("192.168.1.1"));
        assertTrue(IpAddressValidator.isValid("255.255.255.255"));
    }

    @Test
    void acceptsValidIpv6Addresses() {
        assertTrue(IpAddressValidator.isValid("::1"));
        assertTrue(IpAddressValidator.isValid("2001:db8::1"));
        assertTrue(IpAddressValidator.isValid("fe80::1"));
    }

    @Test
    void rejectsOutOfRangeIpv4() {
        assertFalse(IpAddressValidator.isValid("256.1.1.1"));
        assertFalse(IpAddressValidator.isValid("1.2.3.999"));
    }

    @Test
    void rejectsMalformedAddresses() {
        assertFalse(IpAddressValidator.isValid("1.2.3"));
        assertFalse(IpAddressValidator.isValid("not-an-ip"));
        assertFalse(IpAddressValidator.isValid(""));
        assertFalse(IpAddressValidator.isValid(null));
    }

    @Test
    void rejectsOverlyLongInput() {
        assertFalse(IpAddressValidator.isValid("a".repeat(65)));
    }

    @Test
    void requireValidReturnsTheAddress() {
        assertEquals("203.0.113.5", IpAddressValidator.requireValid("203.0.113.5"));
    }

    @Test
    void requireValidRejectsInvalidAddressWith400() {
        GatewayRequestException exception = assertThrows(GatewayRequestException.class,
                () -> IpAddressValidator.requireValid("bad-ip"));

        assertEquals(400, exception.getStatus().value());
        assertEquals("invalid_ip", exception.getCode());
    }
}
