package com.fintech.fintech_gateway.web;

import java.util.regex.Pattern;

public final class IpAddressValidator {

    private static final Pattern IPV4 = Pattern.compile(
            "^(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}$");
    private static final Pattern IPV6 = Pattern.compile(
            "^(([0-9a-fA-F]{1,4}:){7}[0-9a-fA-F]{1,4}|" +
            "([0-9a-fA-F]{1,4}:){1,7}:|" +
            "([0-9a-fA-F]{1,4}:){1,6}:[0-9a-fA-F]{1,4}|" +
            "([0-9a-fA-F]{1,4}:){1,5}(:[0-9a-fA-F]{1,4}){1,2}|" +
            "([0-9a-fA-F]{1,4}:){1,4}(:[0-9a-fA-F]{1,4}){1,3}|" +
            "([0-9a-fA-F]{1,4}:){1,3}(:[0-9a-fA-F]{1,4}){1,4}|" +
            "([0-9a-fA-F]{1,4}:){1,2}(:[0-9a-fA-F]{1,4}){1,5}|" +
            "[0-9a-fA-F]{1,4}:((:[0-9a-fA-F]{1,4}){1,6})|" +
            ":((:[0-9a-fA-F]{1,4}){1,7}|:)|" +
            "fe80:(:[0-9a-fA-F]{0,4}){0,4}%[0-9a-zA-Z]+|" +
            "::(ffff(:0{1,4}){0,1}:){0,1}" +
            "((25[0-5]|(2[0-4]|1{0,1}[0-9]){0,1}[0-9])\\.){3}" +
            "(25[0-5]|(2[0-4]|1{0,1}[0-9]){0,1}[0-9])|" +
            "([0-9a-fA-F]{1,4}:){1,4}:" +
            "((25[0-5]|(2[0-4]|1{0,1}[0-9]){0,1}[0-9])\\.){3}" +
            "(25[0-5]|(2[0-4]|1{0,1}[0-9]){0,1}[0-9]))$");

    private IpAddressValidator() {}

    public static boolean isValid(String value) {
        if (value == null || value.isBlank() || value.length() > 64) {
            return false;
        }
        return IPV4.matcher(value).matches() || IPV6.matcher(value).matches();
    }

    public static String requireValid(String value) {
        if (!isValid(value)) {
            throw new GatewayRequestException(org.springframework.http.HttpStatus.BAD_REQUEST,
                    "invalid_ip", "A valid IPv4 or IPv6 address is required.");
        }
        return value;
    }
}
