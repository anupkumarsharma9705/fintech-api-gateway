package com.fintech.fintech_gateway.compliance;

import com.fintech.fintech_gateway.audit.AuditLogger;
import com.fintech.fintech_gateway.audit.AuditLogger.AuditEvent;
import com.fintech.fintech_gateway.config.GatewayProperties;
import com.fintech.fintech_gateway.metrics.GatewayMetrics;
import com.fintech.fintech_gateway.persistence.entity.AuditLogEntity;
import com.fintech.fintech_gateway.persistence.repository.AuditLogRepository;
import com.fintech.fintech_gateway.persistence.repository.CountByValue;
import com.fintech.fintech_gateway.persistence.repository.TrustHistoryRepository;
import com.fintech.fintech_gateway.support.TestProperties;
import com.fintech.fintech_gateway.web.GatewayRequestException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ComplianceServiceTest {

    private AuditLogRepository auditLogRepository;
    private TrustHistoryRepository trustHistoryRepository;
    private AuditLogger auditLogger;
    private GatewayMetrics metrics;
    private GatewayProperties properties;
    private ComplianceService service;

    @BeforeEach
    void setUp() {
        auditLogRepository = mock(AuditLogRepository.class);
        trustHistoryRepository = mock(TrustHistoryRepository.class);
        auditLogger = mock(AuditLogger.class);
        metrics = mock(GatewayMetrics.class);
        properties = TestProperties.defaults();
        service = newService(properties);
    }

    @Test
    void summaryAggregatesDecisionsAttacksAuthFailuresAndCoverage() {
        Instant to = Instant.now().minusSeconds(30);
        Instant from = to.minus(Duration.ofHours(1));
        stubSummaryCounts();

        ComplianceService.ComplianceSummary summary = service.summary(from, to);

        assertEquals(10, summary.totalRequests());
        assertEquals(8, summary.allowedRequests());
        assertEquals(0, summary.throttledRequests());
        assertEquals(2, summary.blockedRequests());
        assertEquals(2, summary.wafDetections());
        assertEquals(1, summary.rateLimitRejections());
        assertEquals(0, summary.idempotencyReplays());
        assertEquals(1, summary.authenticationFailures());
        assertEquals(2, summary.distinctTenants());
        assertEquals(1, summary.distinctClients());
        assertEquals(2, summary.trustTierChanges());
        assertEquals(Map.of("ALLOW", 8L, "BLOCK", 2L), summary.decisions());
        assertEquals(Map.of("AUTHENTICATION_FAILED", 1L,
                "RATE_LIMITED", 1L, "SQL_INJECTION", 2L), summary.attackTypes());
        assertEquals(Map.of("HIGH", 1L, "LOW", 1L), summary.trustTransitions());
        assertEquals(new ComplianceService.EvidenceCoverage(
                10, 3, 2,
                Instant.parse("2026-05-01T10:00:00Z"),
                Instant.parse("2026-05-01T11:00:00Z")), summary.coverage());
        assertTrue(summary.attestation().contains("not a certification"));
    }

    @Test
    void wafDetectionsCountOnlyWafAttackTypes() {
        Instant to = Instant.now().minusSeconds(30);
        Instant from = to.minus(Duration.ofHours(1));
        stubSummaryCounts();

        ComplianceService.ComplianceSummary summary = service.summary(from, to);

        assertEquals(2, summary.wafDetections());
        // Positive allowlist: policy events such as METHOD_NOT_ALLOWED,
        // BLACKLISTED or rate limiting must never inflate WAF detections.
        verify(auditLogRepository).countByAttackTypeInAndTimestampBetween(
                eq(Set.of("SQL_INJECTION", "XSS", "PATH_TRAVERSAL", "COMMAND_INJECTION")),
                any(), any());
    }

    @Test
    void nullWindowDefaultsToTheConfiguredLookback() {
        stubEmptySummary();

        ComplianceService.ComplianceSummary summary = service.summary(null, null);

        assertEquals(Duration.ofHours(24),
                Duration.between(summary.from(), summary.to()));
    }

    @Test
    void invertedWindowIsRejected() {
        Instant now = Instant.now();
        GatewayRequestException error = assertThrows(GatewayRequestException.class,
                () -> service.summary(now, now.minusSeconds(60)));

        assertEquals(400, error.getStatus().value());
        assertEquals("invalid_time_range", error.getCode());
        verifyNoInteractions(auditLogRepository, trustHistoryRepository);
    }

    @Test
    void futureWindowIsRejected() {
        Instant now = Instant.now();
        GatewayRequestException error = assertThrows(GatewayRequestException.class,
                () -> service.summary(now.minusSeconds(60), now.plus(Duration.ofMinutes(10))));

        assertEquals("future_time_range", error.getCode());
    }

    @Test
    void windowLargerThanTheMaximumIsRejected() {
        Instant now = Instant.now();
        GatewayRequestException error = assertThrows(GatewayRequestException.class,
                () -> service.summary(now.minus(Duration.ofDays(91)), now));

        assertEquals("range_too_large", error.getCode());
    }

    @Test
    void csvExportIsBoundedAndReportsTruncation() {
        Instant to = Instant.now().minusSeconds(30);
        Instant from = to.minus(Duration.ofHours(1));
        when(auditLogRepository.findByTimestampBetweenOrderByTimestampAsc(
                any(), any(), any(Pageable.class)))
                .thenReturn(List.of(entity(1, "tenant-a", "client-a",
                                "=cmd|' /C calc'!A0"),
                        entity(2, "tenant-b", null, "/api/payments"),
                        entity(3, "tenant-a", "client-a", "/api/account")));

        ComplianceService.CsvExport export = service.auditCsv(from, to, 2);

        assertTrue(export.truncated());
        assertEquals(2, export.rows());
        assertTrue(export.body().startsWith(
                "id,timestamp,tenant_id,client_id,ip,method,uri,decision,"));
        assertTrue(export.body().contains("\"'=cmd|' /C calc'!A0\""));
        assertTrue(export.body().contains("\"tenant-a\",\"client-a\""));
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(auditLogRepository).findByTimestampBetweenOrderByTimestampAsc(
                any(), any(), pageable.capture());
        assertEquals(3, pageable.getValue().getPageSize());
    }

    @Test
    void csvExportUsesTheDefaultRowLimitWhenNoneIsRequested() {
        properties.getCompliance().setDefaultRows(1);
        properties.getCompliance().setMaxRows(2);
        service = newService(properties);
        Instant to = Instant.now().minusSeconds(30);
        Instant from = to.minus(Duration.ofHours(1));
        when(auditLogRepository.findByTimestampBetweenOrderByTimestampAsc(
                any(), any(), any(Pageable.class)))
                .thenReturn(List.of(entity(1, "t", "c", "/a"),
                        entity(2, "t", "c", "/b")));

        ComplianceService.CsvExport export = service.auditCsv(from, to, null);

        assertEquals(1, export.rows());
        assertTrue(export.truncated());
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(auditLogRepository).findByTimestampBetweenOrderByTimestampAsc(
                any(), any(), pageable.capture());
        assertEquals(2, pageable.getValue().getPageSize());
    }

    @Test
    void requestedRowLimitIsClampedToTheMaximum() {
        properties.getCompliance().setDefaultRows(1);
        properties.getCompliance().setMaxRows(2);
        service = newService(properties);
        Instant to = Instant.now().minusSeconds(30);
        Instant from = to.minus(Duration.ofHours(1));
        when(auditLogRepository.findByTimestampBetweenOrderByTimestampAsc(
                any(), any(), any(Pageable.class)))
                .thenReturn(List.of(entity(1, "t", "c", "/a"),
                        entity(2, "t", "c", "/b"), entity(3, "t", "c", "/c")));

        ComplianceService.CsvExport export = service.auditCsv(from, to, 100);

        assertEquals(2, export.rows());
        assertTrue(export.truncated());
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(auditLogRepository).findByTimestampBetweenOrderByTimestampAsc(
                any(), any(), pageable.capture());
        assertEquals(3, pageable.getValue().getPageSize());
    }

    @Test
    void nonPositiveRowLimitIsRejected() {
        Instant to = Instant.now().minusSeconds(30);
        Instant from = to.minus(Duration.ofHours(1));

        GatewayRequestException error = assertThrows(GatewayRequestException.class,
                () -> service.auditCsv(from, to, 0));

        assertEquals("invalid_limit", error.getCode());
        verifyNoInteractions(auditLogRepository);
    }

    @Test
    void csvNeutralizesEverySpreadsheetFormulaPrefix() {
        assertEquals("\"'=SUM(A1)\"", ComplianceService.csv("=SUM(A1)"));
        assertEquals("\"'+1\"", ComplianceService.csv("+1"));
        assertEquals("\"'-1\"", ComplianceService.csv("-1"));
        assertEquals("\"'@cmd\"", ComplianceService.csv("@cmd"));
        assertEquals("\"' =SUM(A1)\"", ComplianceService.csv(" =SUM(A1)"));
        assertEquals("\"a\"\"b\"", ComplianceService.csv("a\"b"));
        assertEquals("\"line1\nline2\"", ComplianceService.csv("line1\nline2"));
        assertEquals("\"\"", ComplianceService.csv(null));
    }

    @Test
    void recordExportWritesAnOperationalAuditEventAndMetric() {
        service.recordExport("203.0.113.5", "GET",
                "/compliance/evidence/summary", "req-1", 7, "summary");

        verify(metrics).recordComplianceExport("summary");
        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditLogger).log(captor.capture());
        AuditEvent event = captor.getValue();
        assertEquals("COMPLIANCE_EXPORT", event.getAttackType());
        assertEquals("internal", event.getTenantId());
        assertEquals("internal-ops", event.getClientId());
        assertEquals(200, event.getStatusCode());
    }

    @Test
    void invalidComplianceConfigurationIsRejectedAtConstruction() {
        GatewayProperties invalid = TestProperties.defaults();
        invalid.getCompliance().setDefaultWindow(Duration.ZERO);
        assertThrows(IllegalArgumentException.class, () -> newService(invalid));

        GatewayProperties smallerMax = TestProperties.defaults();
        smallerMax.getCompliance().setMaxWindow(Duration.ofHours(1));
        assertThrows(IllegalArgumentException.class, () -> newService(smallerMax));

        GatewayProperties zeroRows = TestProperties.defaults();
        zeroRows.getCompliance().setDefaultRows(0);
        assertThrows(IllegalArgumentException.class, () -> newService(zeroRows));

        GatewayProperties invertedRows = TestProperties.defaults();
        invertedRows.getCompliance().setDefaultRows(10);
        invertedRows.getCompliance().setMaxRows(5);
        assertThrows(IllegalArgumentException.class, () -> newService(invertedRows));
    }

    @Test
    void csvIncludesEveryColumnAndQuotesValues() {
        Instant to = Instant.now().minusSeconds(30);
        Instant from = to.minus(Duration.ofHours(1));
        when(auditLogRepository.findByTimestampBetweenOrderByTimestampAsc(
                any(), any(), any(Pageable.class)))
                .thenReturn(List.of(entity(7, "tenant-a", "client-a", "/api/payments")));

        String csv = service.auditCsv(from, to, 10).body();

        String[] lines = csv.split("\n");
        assertEquals(2, lines.length);
        assertEquals(13, lines[1].split(",", -1).length);
        assertTrue(lines[1].startsWith("\"7\","));
        assertTrue(lines[1].contains("\"tenant-a\",\"client-a\""));
        assertFalse(lines[1].contains("null"));
    }

    private ComplianceService newService(GatewayProperties source) {
        return new ComplianceService(auditLogRepository, trustHistoryRepository,
                auditLogger, source, metrics);
    }

    private void stubSummaryCounts() {
        when(auditLogRepository.countByDecisionBetween(any(), any()))
                .thenReturn(List.of(count("ALLOW", 8), count("BLOCK", 2)));
        when(auditLogRepository.countByAttackTypeBetween(any(), any()))
                .thenReturn(List.of(count("SQL_INJECTION", 2), count("RATE_LIMITED", 1),
                        count("AUTHENTICATION_FAILED", 1)));
        when(trustHistoryRepository.countByNewTierBetween(any(), any()))
                .thenReturn(List.of(count("HIGH", 1), count("LOW", 1)));
        when(auditLogRepository.countByTimestampBetween(any(), any())).thenReturn(10L);
        when(auditLogRepository.countByAttackTypeInAndTimestampBetween(any(), any(), any()))
                .thenAnswer(invocation -> {
                    Collection<?> types = invocation.getArgument(0);
                    if (types.contains("SQL_INJECTION")) {
                        return 2L;
                    }
                    if (types.contains("RATE_LIMITED")) {
                        return 1L;
                    }
                    if (types.contains("AUTHENTICATION_FAILED")) {
                        return 1L;
                    }
                    return 0L;
                });
        when(auditLogRepository.countDistinctTenantsBetween(any(), any()))
                .thenReturn(2L);
        when(auditLogRepository.countDistinctClientsBetween(any(), any()))
                .thenReturn(1L);
        when(auditLogRepository.countByClientIdIsNullAndTimestampBetween(any(), any()))
                .thenReturn(3L);
        when(trustHistoryRepository.countByTimestampBetween(any(), any())).thenReturn(2L);
        when(auditLogRepository.findFirstByTimestampBetweenOrderByTimestampAsc(any(), any()))
                .thenReturn(Optional.of(entity(1, "tenant-a", "client-a", "/a")));
        when(auditLogRepository.findFirstByTimestampBetweenOrderByTimestampDesc(any(), any()))
                .thenReturn(Optional.of(entityAt(Instant.parse("2026-05-01T11:00:00Z"),
                        2, "tenant-a", "client-a", "/b")));
    }

    private void stubEmptySummary() {
        when(auditLogRepository.countByDecisionBetween(any(), any())).thenReturn(List.of());
        when(auditLogRepository.countByAttackTypeBetween(any(), any())).thenReturn(List.of());
        when(trustHistoryRepository.countByNewTierBetween(any(), any())).thenReturn(List.of());
        when(auditLogRepository.countByTimestampBetween(any(), any())).thenReturn(0L);
        when(auditLogRepository.countByAttackTypeInAndTimestampBetween(any(), any(), any()))
                .thenReturn(0L);
        when(auditLogRepository.countDistinctTenantsBetween(any(), any()))
                .thenReturn(0L);
        when(auditLogRepository.countDistinctClientsBetween(any(), any()))
                .thenReturn(0L);
        when(auditLogRepository.countByClientIdIsNullAndTimestampBetween(any(), any()))
                .thenReturn(0L);
        when(trustHistoryRepository.countByTimestampBetween(any(), any())).thenReturn(0L);
        when(auditLogRepository.findFirstByTimestampBetweenOrderByTimestampAsc(any(), any()))
                .thenReturn(Optional.empty());
        when(auditLogRepository.findFirstByTimestampBetweenOrderByTimestampDesc(any(), any()))
                .thenReturn(Optional.empty());
    }

    private CountByValue count(String value, long total) {
        return new Count(value, total);
    }

    private AuditLogEntity entity(long id, String tenantId, String clientId, String uri) {
        return entityAt(Instant.parse("2026-05-01T10:00:00Z"),
                id, tenantId, clientId, uri);
    }

    private AuditLogEntity entityAt(Instant timestamp, long id, String tenantId,
                                    String clientId, String uri) {
        AuditLogEntity entity = new AuditLogEntity(
                timestamp, "203.0.113.5", "POST", uri,
                10, "ALLOW", null, 200, tenantId, clientId, "req-" + id, 5L);
        ReflectionTestUtils.setField(entity, "id", id);
        return entity;
    }

    private record Count(String value, long total) implements CountByValue {
        @Override
        public String getValue() {
            return value;
        }

        @Override
        public long getTotal() {
            return total;
        }
    }
}
