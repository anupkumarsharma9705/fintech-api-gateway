package com.fintech.fintech_gateway.metrics;

import com.fintech.fintech_gateway.risk.RiskDecisionEngine.Decision;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * Low-cardinality gateway metrics. Tenant and IP are intentionally not tags:
 * both are unbounded and would explode the Prometheus time-series count.
 * They remain available in structured logs and the audit table.
 */
@Component
public class GatewayMetrics {

    private final MeterRegistry registry;

    public GatewayMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void recordRequest(Decision decision, int status, long latencyNanos) {
        registry.counter("gateway.requests",
                "decision", decision.name(),
                "status", String.valueOf(status)).increment();
        registry.timer("gateway.request.duration", "decision", decision.name())
                .record(latencyNanos, TimeUnit.NANOSECONDS);
    }

    public void recordWafBlock(String threatType) {
        registry.counter("gateway.waf.blocked",
                "threat", threatType == null ? "UNKNOWN" : threatType).increment();
    }

    public void recordRateLimited() {
        registry.counter("gateway.rate.limited").increment();
    }

    public void recordIdempotencyReplay() {
        registry.counter("gateway.idempotency.replay").increment();
    }

    public void recordIdempotencyStoreFailure() {
        registry.counter("gateway.idempotency.store.failure").increment();
    }

    public void recordIdempotencyReleaseFailure() {
        registry.counter("gateway.idempotency.release.failure").increment();
    }

    public void recordUpstreamError() {
        registry.counter("gateway.upstream.errors").increment();
    }

    public void recordSecurityDependencyFailure() {
        registry.counter("gateway.security.dependency.failure").increment();
    }

    public void recordAuthFailure(String reason) {
        registry.counter("gateway.auth.failures",
                "reason", reason == null ? "UNKNOWN" : reason).increment();
    }

    public void recordComplianceExport(String type) {
        registry.counter("gateway.compliance.export",
                "type", type == null ? "unknown" : type).increment();
    }
}
