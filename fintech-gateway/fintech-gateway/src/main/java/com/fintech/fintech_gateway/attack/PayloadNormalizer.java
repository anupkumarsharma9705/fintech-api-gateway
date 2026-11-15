package com.fintech.fintech_gateway.attack;

import com.fintech.fintech_gateway.config.GatewayProperties;
import com.fintech.fintech_gateway.web.GatewayRequestException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Normalizes attacker-controlled input before signature matching. Without
 * this, percent-encoding, double encoding, HTML entities and Unicode
 * compatibility characters trivially bypass regex-based WAF rules.
 */
@Component
public class PayloadNormalizer {

    private static final int MAX_DECODE_PASSES = 3;
    private static final Pattern CONTROL = Pattern.compile("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u007F]");
    private static final Pattern WHITESPACE = Pattern.compile("\\s{2,}");
    private static final Pattern ENTITY = Pattern.compile(
            "&(?:#x([0-9a-fA-F]+)|#(\\d+)|(lt|gt|quot|apos|amp));");
    private static final Map<String, String> NAMED_ENTITIES = Map.of(
            "lt", "<", "gt", ">", "quot", "\"", "apos", "'", "amp", "&");

    private final int maxLength;

    public PayloadNormalizer(GatewayProperties properties) {
        this.maxLength = Math.max(properties.getSecurity().getMaxBodyBytes(), 8_192);
    }

    public String normalize(String input) {
        if (input == null || input.isEmpty()) {
            return "";
        }
        if (input.length() > maxLength) {
            throw new GatewayRequestException(HttpStatus.PAYLOAD_TOO_LARGE,
                    "payload_too_large", "Request payload exceeds the configured limit.");
        }

        String value = input;
        for (int i = 0; i < MAX_DECODE_PASSES; i++) {
            String decoded = percentDecode(value);
            if (decoded.equals(value)) {
                break;
            }
            value = decoded;
        }
        value = decodeEntities(value);
        value = Normalizer.normalize(value, Normalizer.Form.NFKC);
        value = CONTROL.matcher(value).replaceAll(" ");
        value = WHITESPACE.matcher(value).replaceAll(" ");
        return value;
    }

    private String percentDecode(String value) {
        try {
            // Preserve literal '+' (valid JSON content) while decoding %XX.
            return URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException malformed) {
            // Leave malformed encodings intact so the WAF can still inspect
            // the raw text; malformed input is not allowed to crash the filter.
            return value;
        }
    }

    private String decodeEntities(String value) {
        Matcher matcher = ENTITY.matcher(value);
        StringBuilder result = new StringBuilder(value.length());
        while (matcher.find()) {
            String replacement;
            if (matcher.group(1) != null) {
                replacement = codePoint(matcher.group(1), 16);
            } else if (matcher.group(2) != null) {
                replacement = codePoint(matcher.group(2), 10);
            } else {
                replacement = NAMED_ENTITIES.getOrDefault(matcher.group(3), matcher.group());
            }
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private String codePoint(String value, int radix) {
        try {
            int codePoint = Integer.parseInt(value, radix);
            if (Character.isValidCodePoint(codePoint)) {
                return new String(Character.toChars(codePoint));
            }
        } catch (NumberFormatException ignored) {
            // fall through
        }
        return "";
    }
}
