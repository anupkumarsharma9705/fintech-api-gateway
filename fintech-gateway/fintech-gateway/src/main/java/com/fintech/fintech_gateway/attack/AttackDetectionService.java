package com.fintech.fintech_gateway.attack;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Signature-based first line of defence. Patterns deliberately target
 * injection syntax rather than isolated keywords (for example, "select" in
 * a product description must not be treated as SQL injection).
 */
@Service
public class AttackDetectionService {

    private static final List<Pattern> SQL_INJECTION = List.of(
            Pattern.compile("(?i)(\\bunion\\b\\s+(all\\s+)?\\bselect\\b)"),
            Pattern.compile("(?i)(\\b(or|and)\\b\\s+['\"]?\\d+['\"]?\\s*=\\s*['\"]?\\d+)"),
            Pattern.compile("(?i)(['\"]\\s*(or|and)\\s*['\"]?\\d)"),
            Pattern.compile("(?i)(\\b(drop|truncate|alter)\\b\\s+\\b(table|database|schema)\\b)"),
            Pattern.compile("(?i)(\\b(exec|execute)\\b\\s*\\(|\\bxp_\\w+|\\bsp_\\w+)"),
            Pattern.compile("(?i)(--\\s|/\\*.*?\\*/|;\\s*(drop|delete|update|insert|select|alter|truncate)\\b)"),
            Pattern.compile("(?i)(\\bwaitfor\\s+delay\\b|\\bsleep\\s*\\(|\\bbenchmark\\s*\\()")
    );

    private static final List<Pattern> XSS = List.of(
            Pattern.compile("(?i)(<script\\b|</script\\s*>|javascript\\s*:|vbscript\\s*:" +
                    "|on(error|load|click|mouseover|focus|submit)\\s*=" +
                    "|<iframe\\b|<object\\b|<embed\\b|<svg\\b[^>]*\\bon\\w+\\s*=)"),
            Pattern.compile("(?i)(document\\.cookie|document\\.domain|window\\.location" +
                    "|eval\\s*\\(|alert\\s*\\(|prompt\\s*\\(|confirm\\s*\\()")
    );

    private static final List<Pattern> PATH_TRAVERSAL = List.of(
            Pattern.compile("(^|[/\\\\])\\.\\.([/\\\\]|$)"),
            Pattern.compile("(?i)(/etc/passwd|/etc/shadow|windows/system32|winnt/system32|WEB-INF|META-INF)")
    );

    private static final List<Pattern> COMMAND_INJECTION = List.of(
            Pattern.compile("(?i)([;&|]\\s*(cat|ls|wget|curl|bash|sh|nc|ncat|python|perl|powershell)\\b" +
                    "|\\$\\(|`[^`]+`)")
    );

    private final PayloadNormalizer normalizer;

    public AttackDetectionService(PayloadNormalizer normalizer) {
        this.normalizer = normalizer;
    }

    public AttackDetectionResult inspect(String payload, String uri) {
        return inspect(payload, uri, null);
    }

    public AttackDetectionResult inspect(String payload, String uri, String query) {
        String normalizedUri = normalizer.normalize(uri);
        String normalizedQuery = normalizer.normalize(query);
        String normalizedPayload = normalizer.normalize(payload);

        String pathTarget = normalizedUri + (normalizedQuery.isEmpty() ? "" : "?" + normalizedQuery);
        if (matchesAny(PATH_TRAVERSAL, pathTarget)) {
            return AttackDetectionResult.threat("PATH_TRAVERSAL");
        }

        if (matchesAny(XSS, normalizedPayload) || matchesAny(XSS, normalizedQuery)) {
            return AttackDetectionResult.threat("XSS");
        }
        if (matchesAny(SQL_INJECTION, normalizedPayload) || matchesAny(SQL_INJECTION, normalizedQuery)) {
            return AttackDetectionResult.threat("SQL_INJECTION");
        }
        if (matchesAny(COMMAND_INJECTION, normalizedPayload) || matchesAny(COMMAND_INJECTION, normalizedQuery)) {
            return AttackDetectionResult.threat("COMMAND_INJECTION");
        }
        return AttackDetectionResult.clean();
    }

    private boolean matchesAny(List<Pattern> patterns, String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        for (Pattern pattern : patterns) {
            if (pattern.matcher(value).find()) {
                return true;
            }
        }
        return false;
    }

    public static class AttackDetectionResult {
        private final boolean threatDetected;
        private final String threatType;

        private AttackDetectionResult(boolean threatDetected, String threatType) {
            this.threatDetected = threatDetected;
            this.threatType = threatType;
        }

        public static AttackDetectionResult clean() {
            return new AttackDetectionResult(false, null);
        }

        public static AttackDetectionResult threat(String type) {
            return new AttackDetectionResult(true, type);
        }

        public boolean isThreatDetected() { return threatDetected; }
        public String getThreatType() { return threatType; }
    }
}
