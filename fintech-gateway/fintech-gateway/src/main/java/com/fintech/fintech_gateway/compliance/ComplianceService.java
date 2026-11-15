package com.fintech.fintech_gateway.compliance;

import com.fintech.fintech_gateway.audit.AuditLogger;
import com.fintech.fintech_gateway.audit.AuditLogger.AuditEvent;
import com.fintech.fintech_gateway.config.GatewayProperties;
import com.fintech.fintech_gateway.metrics.GatewayMetrics;
import com.fintech.fintech_gateway.persistence.entity.AuditLogEntity;
import com.fintech.fintech_gateway.persistence.repository.AuditLogRepository;
import com.fintech.fintech_gateway.persistence.repository.CountByValue;
import com.fintech.fintech_gateway.persistence.repository.TrustHistoryRepository;
import com.fintech.fintech_gateway.risk.RiskDecisionEngine.Decision;
import com.fintech.fintech_gateway.web.GatewayRequestException;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Builds bounded, control-oriented evidence from the gateway's own runtime
 * data. This is evidence, not an attestation: it proves what the gateway
 * observed and decided during a window, and it deliberately makes no claim
 * that a customer is PCI-DSS, RBI or NPCI compliant.
 */
@Service
public class ComplianceService {

    /**
     * Attack types emitted by {@code AttackDetectionService}. This is an
     * allowlist, not an exclusion list: policy events such as rate limits,
     * replays, method rejections and auth failures are deliberately not WAF
     * detections, and new operational event types must not inflate this count
     * by being forgotten here.
     */
    private static final Set<String> WAF_ATTACK_TYPES = Set.of(
            "SQL_INJECTION", "XSS", "PATH_TRAVERSAL", "COMMAND_INJECTION");

    private static final Set<String> AUTH_FAILURE_TYPES = Set.of(
            "AUTHENTICATION_FAILED", "INSUFFICIENT_SCOPE", "INVALID_TOKEN_CLAIMS");

    private static final Set<String> RATE_LIMIT_TYPES = Set.of(
            "RATE_LIMITED", "THROTTLE_EXCEEDED");

    private static final Set<String> IDEMPOTENCY_REPLAY_TYPES = Set.of("DUPLICATE");

    private static final Duration FUTURE_TOLERANCE = Duration.ofMinutes(5);

    private static final String ATTESTATION =
            "Runtime evidence derived from gateway audit logs. This export supports "
                    + "control testing; it is not a certification or an audit opinion.";

    private static final String CSV_HEADER =
            "id,timestamp,tenant_id,client_id,ip,method,uri,decision,"
                    + "risk_score,attack_type,status_code,latency_ms,request_id\n";

    private final AuditLogRepository auditLogRepository;
    private final TrustHistoryRepository trustHistoryRepository;
    private final AuditLogger auditLogger;
    private final GatewayMetrics metrics;
    private final Duration defaultWindow;
    private final Duration maxWindow;
    private final int defaultRows;
    private final int maxRows;

    public ComplianceService(AuditLogRepository auditLogRepository,
                             TrustHistoryRepository trustHistoryRepository,
                             AuditLogger auditLogger,
                             GatewayProperties properties,
                             GatewayMetrics metrics) {
        this.auditLogRepository = auditLogRepository;
        this.trustHistoryRepository = trustHistoryRepository;
        this.auditLogger = auditLogger;
        this.metrics = metrics;
        this.defaultWindow = properties.getCompliance().getDefaultWindow();
        this.maxWindow = properties.getCompliance().getMaxWindow();
        this.defaultRows = properties.getCompliance().getDefaultRows();
        this.maxRows = properties.getCompliance().getMaxRows();

        if (defaultWindow == null || defaultWindow.isNegative() || defaultWindow.isZero()) {
            throw new IllegalArgumentException("gateway.compliance.default-window must be positive");
        }
        if (maxWindow == null || maxWindow.isNegative() || maxWindow.isZero()
                || maxWindow.compareTo(defaultWindow) < 0) {
            throw new IllegalArgumentException(
                    "gateway.compliance.max-window must be positive and at least the default window");
        }
        if (defaultRows <= 0 || maxRows <= 0 || defaultRows > maxRows) {
            throw new IllegalArgumentException(
                    "gateway.compliance row limits must be positive and default-rows <= max-rows");
        }
    }

    public ComplianceSummary summary(Instant from, Instant to) {
        Window window = normalize(from, to);
        Map<String, Long> decisions = toMap(
                auditLogRepository.countByDecisionBetween(window.from(), window.to()));
        Map<String, Long> attackTypes = toMap(
                auditLogRepository.countByAttackTypeBetween(window.from(), window.to()));
        Map<String, Long> trustTransitions = toMap(
                trustHistoryRepository.countByNewTierBetween(window.from(), window.to()));

        long totalRequests = auditLogRepository.countByTimestampBetween(
                window.from(), window.to());
        long wafDetections = auditLogRepository
                .countByAttackTypeInAndTimestampBetween(
                        WAF_ATTACK_TYPES, window.from(), window.to());
        long rateLimitRejections = auditLogRepository
                .countByAttackTypeInAndTimestampBetween(
                        RATE_LIMIT_TYPES, window.from(), window.to());
        long idempotencyReplays = auditLogRepository
                .countByAttackTypeInAndTimestampBetween(
                        IDEMPOTENCY_REPLAY_TYPES, window.from(), window.to());
        long authenticationFailures = auditLogRepository
                .countByAttackTypeInAndTimestampBetween(
                        AUTH_FAILURE_TYPES, window.from(), window.to());
        long distinctTenants = auditLogRepository
                .countDistinctTenantsBetween(window.from(), window.to());
        long distinctClients = auditLogRepository
                .countDistinctClientsBetween(window.from(), window.to());
        long legacyRows = auditLogRepository
                .countByClientIdIsNullAndTimestampBetween(window.from(), window.to());
        long trustTierChanges = trustHistoryRepository.countByTimestampBetween(
                window.from(), window.to());

        Instant earliest = auditLogRepository
                .findFirstByTimestampBetweenOrderByTimestampAsc(window.from(), window.to())
                .map(AuditLogEntity::getTimestamp)
                .orElse(null);
        Instant latest = auditLogRepository
                .findFirstByTimestampBetweenOrderByTimestampDesc(window.from(), window.to())
                .map(AuditLogEntity::getTimestamp)
                .orElse(null);

        return new ComplianceSummary(
                Instant.now(),
                window.from(),
                window.to(),
                ATTESTATION,
                totalRequests,
                decisions.getOrDefault("ALLOW", 0L),
                decisions.getOrDefault("THROTTLE", 0L),
                decisions.getOrDefault("BLOCK", 0L),
                wafDetections,
                rateLimitRejections,
                idempotencyReplays,
                authenticationFailures,
                distinctTenants,
                distinctClients,
                trustTierChanges,
                decisions,
                attackTypes,
                trustTransitions,
                new EvidenceCoverage(totalRequests, legacyRows, trustTierChanges,
                        earliest, latest));
    }

    public CsvExport auditCsv(Instant from, Instant to, Integer requestedLimit) {
        Window window = normalize(from, to);
        int limit = resolveLimit(requestedLimit);
        // Fetch one extra row so the response can honestly say it was bounded.
        List<AuditLogEntity> rows = auditLogRepository
                .findByTimestampBetweenOrderByTimestampAsc(
                        window.from(), window.to(), PageRequest.of(0, limit + 1));
        boolean truncated = rows.size() > limit;
        if (truncated) {
            rows = rows.subList(0, limit);
        }

        StringBuilder csv = new StringBuilder(CSV_HEADER.length() + rows.size() * 256);
        csv.append(CSV_HEADER);
        for (AuditLogEntity row : rows) {
            csv.append(csv(row.getId()))
                    .append(',').append(csv(row.getTimestamp()))
                    .append(',').append(csv(row.getTenantId()))
                    .append(',').append(csv(row.getClientId()))
                    .append(',').append(csv(row.getIp()))
                    .append(',').append(csv(row.getMethod()))
                    .append(',').append(csv(row.getUri()))
                    .append(',').append(csv(row.getDecision()))
                    .append(',').append(row.getRiskScore())
                    .append(',').append(csv(row.getAttackType()))
                    .append(',').append(row.getStatusCode())
                    .append(',').append(row.getLatencyMs())
                    .append(',').append(csv(row.getRequestId()))
                    .append('\n');
        }
        return new CsvExport(csv.toString(), rows.size(), truncated,
                window.from(), window.to());
    }

    /**
     * Records operator access to the evidence endpoints. The write is
     * best-effort and asynchronous, so it never delays or fails the export.
     */
    public void recordExport(String ip, String method, String uri, String requestId,
                             long latencyMs, String type) {
        metrics.recordComplianceExport(type);
        auditLogger.log(new AuditEvent.Builder()
                .ip(ip)
                .method(method)
                .uri(uri)
                .tenantId("internal")
                .clientId("internal-ops")
                .requestId(requestId)
                .riskScore(0)
                .decision(Decision.ALLOW)
                .attackType("COMPLIANCE_EXPORT")
                .statusCode(200)
                .latencyMs(latencyMs)
                .build());
    }

    Window normalize(Instant from, Instant to) {
        Instant now = Instant.now();
        Instant effectiveTo = to != null ? to : now;
        Instant effectiveFrom = from != null ? from : effectiveTo.minus(defaultWindow);

        if (!effectiveFrom.isBefore(effectiveTo)) {
            throw new GatewayRequestException(HttpStatus.BAD_REQUEST,
                    "invalid_time_range",
                    "The from timestamp must be before the to timestamp.");
        }
        if (effectiveTo.isAfter(now.plus(FUTURE_TOLERANCE))) {
            throw new GatewayRequestException(HttpStatus.BAD_REQUEST,
                    "future_time_range",
                    "The to timestamp cannot be in the future.");
        }
        if (Duration.between(effectiveFrom, effectiveTo).compareTo(maxWindow) > 0) {
            throw new GatewayRequestException(HttpStatus.BAD_REQUEST,
                    "range_too_large",
                    "The requested evidence window exceeds the configured maximum.");
        }
        return new Window(effectiveFrom, effectiveTo);
    }

    private int resolveLimit(Integer requestedLimit) {
        int limit = requestedLimit == null ? defaultRows : requestedLimit;
        if (limit <= 0) {
            throw new GatewayRequestException(HttpStatus.BAD_REQUEST,
                    "invalid_limit", "The row limit must be positive.");
        }
        return Math.min(limit, maxRows);
    }

    private Map<String, Long> toMap(List<CountByValue> counts) {
        Map<String, Long> values = new LinkedHashMap<>();
        for (CountByValue count : counts) {
            if (count.getValue() != null) {
                values.put(count.getValue(), count.getTotal());
            }
        }
        return Collections.unmodifiableMap(new TreeMap<>(values));
    }

    /**
     * Always quotes every CSV field and neutralizes spreadsheet formula
     * prefixes. Audit rows contain attacker-controlled URIs and IPs; a field
     * beginning with {@code =}, {@code +}, {@code -} or {@code @} would
     * execute as a formula when an operator opens the export in Excel.
     */
    static String csv(String value) {
        String safe = value == null ? "" : value.replace("\u0000", "");
        String leading = safe.stripLeading();
        if (!leading.isEmpty() && "=+-@".indexOf(leading.charAt(0)) >= 0) {
            safe = "'" + safe;
        }
        return '"' + safe.replace("\"", "\"\"") + '"';
    }

    private static String csv(Long value) {
        return csv(value == null ? null : value.toString());
    }

    private static String csv(Instant value) {
        return csv(value == null ? null : value.toString());
    }

    public record Window(Instant from, Instant to) {
    }

    public record EvidenceCoverage(long auditRows,
                                   long legacyRowsWithoutClientId,
                                   long trustHistoryRows,
                                   Instant earliestAudit,
                                   Instant latestAudit) {
    }

    public record ComplianceSummary(Instant generatedAt,
                                    Instant from,
                                    Instant to,
                                    String attestation,
                                    long totalRequests,
                                    long allowedRequests,
                                    long throttledRequests,
                                    long blockedRequests,
                                    long wafDetections,
                                    long rateLimitRejections,
                                    long idempotencyReplays,
                                    long authenticationFailures,
                                    long distinctTenants,
                                    long distinctClients,
                                    long trustTierChanges,
                                    Map<String, Long> decisions,
                                    Map<String, Long> attackTypes,
                                    Map<String, Long> trustTransitions,
                                    EvidenceCoverage coverage) {
    }

    public record CsvExport(String body, int rows, boolean truncated,
                            Instant from, Instant to) {
    }
}
