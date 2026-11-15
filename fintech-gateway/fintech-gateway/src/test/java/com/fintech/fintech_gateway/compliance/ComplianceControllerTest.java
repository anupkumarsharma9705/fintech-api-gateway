package com.fintech.fintech_gateway.compliance;

import com.fintech.fintech_gateway.compliance.ComplianceService.ComplianceSummary;
import com.fintech.fintech_gateway.compliance.ComplianceService.CsvExport;
import com.fintech.fintech_gateway.config.GatewayProperties;
import com.fintech.fintech_gateway.support.TestProperties;
import com.fintech.fintech_gateway.web.ClientIpResolver;
import com.fintech.fintech_gateway.web.RequestIdFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ComplianceControllerTest {

    private static final String IP = "203.0.113.5";
    private static final String REQUEST_ID = "req-12345678";

    private ComplianceService complianceService;
    private GatewayProperties properties;
    private ComplianceController controller;

    @BeforeEach
    void setUp() {
        complianceService = mock(ComplianceService.class);
        properties = TestProperties.defaults();
        properties.getCors().setAllowedOrigins(List.of("http://localhost:3000"));
        controller = new ComplianceController(
                complianceService, new ClientIpResolver(properties));
    }

    @Test
    void summaryReturnsTheServiceResultAndRecordsExport() throws Exception {
        Instant from = Instant.parse("2026-05-01T10:00:00Z");
        Instant to = Instant.parse("2026-05-01T11:00:00Z");
        ComplianceSummary summary = new ComplianceSummary(
                Instant.now(), from, to, "attestation",
                10, 8, 0, 2, 2, 1, 0, 1, 2, 1, 2,
                Map.of("ALLOW", 8L, "BLOCK", 2L),
                Map.of("SQL_INJECTION", 2L, "RATE_LIMITED", 1L),
                Map.of("HIGH", 1L),
                new ComplianceService.EvidenceCoverage(10, 3, 2, from, to));
        when(complianceService.summary(from, to)).thenReturn(summary);
        MockHttpServletRequest request = request("GET", "/compliance/evidence/summary");
        request.addParameter("from", "2026-05-01T10:00:00Z");
        request.addParameter("to", "2026-05-01T11:00:00Z");

        var response = controller.summary(from, to, request);

        assertEquals(200, response.getStatusCode().value());
        assertSame(summary, response.getBody());
        assertEquals("no-store", response.getHeaders().getCacheControl());
        verify(complianceService).recordExport(any(), any(), any(), any(), anyLong(), any());
    }

    @Test
    void csvExportReturnsTextCsvWithDispositionAndEvidenceHeaders() throws Exception {
        Instant from = Instant.parse("2026-05-01T10:00:00Z");
        Instant to = Instant.parse("2026-05-01T11:00:00Z");
        String csvBody = "\"id\",\"timestamp\",\"tenant_id\",\"client_id\",\"ip\",\"method\",\"uri\",\"decision\",\"risk_score\",\"attack_type\",\"status_code\",\"latency_ms\",\"request_id\"\n" +
                "\"1\",\"2026-05-01T10:00:00Z\",\"tenant-a\",\"client-a\",\"203.0.113.5\",\"POST\",\"/api/payments\",\"ALLOW\",\"10\",\"\",\"200\",\"5\",\"req-1\"\n";
        CsvExport export = new CsvExport(csvBody, 1, false, from, to);
        when(complianceService.auditCsv(from, to, 50)).thenReturn(export);
        MockHttpServletRequest request = request("GET", "/compliance/evidence/audit.csv");
        request.addParameter("from", "2026-05-01T10:00:00Z");
        request.addParameter("to", "2026-05-01T11:00:00Z");
        request.addParameter("limit", "50");

        var response = controller.auditCsv(from, to, 50, request);

        assertEquals(200, response.getStatusCode().value());
        assertEquals("text/csv;charset=UTF-8", response.getHeaders().getContentType().toString());
        assertTrue(response.getHeaders().getContentDisposition().toString()
                .contains("audit-evidence-" + from.toEpochMilli()
                        + "-" + to.toEpochMilli() + ".csv"));
        assertEquals("1", response.getHeaders().getFirst("X-Evidence-Rows"));
        assertEquals("false", response.getHeaders().getFirst("X-Evidence-Truncated"));
        assertEquals("no-store", response.getHeaders().getCacheControl());
    }

    @Test
    void defaultWindowAndRowLimitAreUsedWhenParametersAreOmitted() throws Exception {
        CsvExport export = new CsvExport("header\nbody\n", 1, false,
                Instant.now().minusSeconds(3600), Instant.now());
        when(complianceService.auditCsv(any(), any(), any())).thenReturn(export);
        MockHttpServletRequest request = request("GET", "/compliance/evidence/audit.csv");

        controller.auditCsv(null, null, null, request);

        verify(complianceService).auditCsv(any(), any(), any());
    }

    private MockHttpServletRequest request(String method, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setRemoteAddr(IP);
        request.setAttribute(RequestIdFilter.ATTRIBUTE, REQUEST_ID);
        return request;
    }
}