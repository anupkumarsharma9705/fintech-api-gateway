package com.fintech.fintech_gateway.compliance;

import com.fintech.fintech_gateway.web.ClientIpResolver;
import com.fintech.fintech_gateway.web.RequestIdFilter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

/**
 * Operator-only evidence export. Both endpoints are gated by
 * {@link com.fintech.fintech_gateway.filter.InternalOpsAuthFilter}; the
 * summary is intentionally small and the CSV export is row- and
 * window-bounded so a single call cannot exhaust gateway memory.
 */
@RestController
@RequestMapping("/compliance/evidence")
@Tag(name = "Compliance evidence",
        description = "Operator-only PCI-DSS/RBI/NPCI evidence derived from gateway runtime data")
@SecurityRequirement(name = "internalOpsKey")
public class ComplianceController {

    private final ComplianceService complianceService;
    private final ClientIpResolver clientIpResolver;

    public ComplianceController(ComplianceService complianceService,
                                ClientIpResolver clientIpResolver) {
        this.complianceService = complianceService;
        this.clientIpResolver = clientIpResolver;
    }

    @GetMapping("/summary")
    @Operation(summary = "Summarize gateway security evidence for a bounded time window")
    public ResponseEntity<ComplianceService.ComplianceSummary> summary(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            HttpServletRequest request) {
        long startNanos = System.nanoTime();
        ComplianceService.ComplianceSummary summary = complianceService.summary(from, to);
        recordExport(request, startNanos, "summary");
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(summary);
    }

    @GetMapping(value = "/audit.csv", produces = "text/csv")
    @Operation(summary = "Export bounded, formula-safe audit evidence as CSV")
    public ResponseEntity<String> auditCsv(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) Integer limit,
            HttpServletRequest request) {
        long startNanos = System.nanoTime();
        ComplianceService.CsvExport export = complianceService.auditCsv(from, to, limit);
        recordExport(request, startNanos, "csv");

        String filename = "audit-evidence-" + export.from().toEpochMilli()
                + "-" + export.to().toEpochMilli() + ".csv";
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + filename + "\"")
                .header("X-Evidence-Rows", String.valueOf(export.rows()))
                .header("X-Evidence-Truncated", String.valueOf(export.truncated()))
                .cacheControl(CacheControl.noStore())
                .body(export.body());
    }

    private void recordExport(HttpServletRequest request, long startNanos, String type) {
        complianceService.recordExport(
                clientIpResolver.resolve(request),
                request.getMethod(),
                request.getRequestURI(),
                RequestIdFilter.get(request),
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos),
                type);
    }
}
