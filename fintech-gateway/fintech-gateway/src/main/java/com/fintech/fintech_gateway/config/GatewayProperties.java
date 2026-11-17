package com.fintech.fintech_gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.List;

/**
 * Typed gateway configuration. Keeping every operational knob in one place
 * makes the service testable without Spring and prevents scattered @Value
 * lookups from drifting between environments.
 */
@Validated
@ConfigurationProperties(prefix = "gateway")
public class GatewayProperties {

    private final Backend backend = new Backend();
    private final Cors cors = new Cors();
    private final Risk risk = new Risk();
    private final RateLimit rateLimit = new RateLimit();
    private final Idempotency idempotency = new Idempotency();
    private final Security security = new Security();
    private final Tenant tenant = new Tenant();
    private final Proxy proxy = new Proxy();
    private final Auth auth = new Auth();
    private final Compliance compliance = new Compliance();
    private final Audit audit = new Audit();

    public Backend getBackend() { return backend; }
    public Cors getCors() { return cors; }
    public Risk getRisk() { return risk; }
    public RateLimit getRateLimit() { return rateLimit; }
    public Idempotency getIdempotency() { return idempotency; }
    public Security getSecurity() { return security; }
    public Tenant getTenant() { return tenant; }
    public Proxy getProxy() { return proxy; }
    public Auth getAuth() { return auth; }
    public Compliance getCompliance() { return compliance; }
    public Audit getAudit() { return audit; }

    public static class Backend {
        private String url = "http://localhost:8080";
        private Duration connectTimeout = Duration.ofSeconds(2);
        private Duration readTimeout = Duration.ofSeconds(10);
        private final Mtls mtls = new Mtls();

        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
        public Duration getConnectTimeout() { return connectTimeout; }
        public void setConnectTimeout(Duration connectTimeout) { this.connectTimeout = connectTimeout; }
        public Duration getReadTimeout() { return readTimeout; }
        public void setReadTimeout(Duration readTimeout) { this.readTimeout = readTimeout; }
        public Mtls getMtls() { return mtls; }
    }

    public static class Mtls {
        private boolean enabled;
        /**
         * Name of the Spring SSL bundle configured under spring.ssl.bundle.*.
         * Secrets live in the SSL bundle, never in gateway-specific config.
         */
        private String bundle = "gateway-backend";

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getBundle() { return bundle; }
        public void setBundle(String bundle) { this.bundle = bundle; }
    }

    public static class Cors {
        private List<String> allowedOrigins = List.of("http://localhost:3000", "http://localhost:8081");
        private List<String> allowedMethods = List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS");
        private List<String> allowedHeaders = List.of(
                "Authorization", "Content-Type", "Accept", "Idempotency-Key",
                "X-Tenant-Id", "X-Request-Id", "X-Internal-Ops-Key");
        private List<String> exposedHeaders = List.of(
                "X-Request-Id", "X-RateLimit-Limit", "X-RateLimit-Remaining",
                "X-RateLimit-Reset", "Retry-After");
        private Duration maxAge = Duration.ofHours(1);

        public List<String> getAllowedOrigins() { return allowedOrigins; }
        public void setAllowedOrigins(List<String> allowedOrigins) { this.allowedOrigins = allowedOrigins; }
        public List<String> getAllowedMethods() { return allowedMethods; }
        public void setAllowedMethods(List<String> allowedMethods) { this.allowedMethods = allowedMethods; }
        public List<String> getAllowedHeaders() { return allowedHeaders; }
        public void setAllowedHeaders(List<String> allowedHeaders) { this.allowedHeaders = allowedHeaders; }
        public List<String> getExposedHeaders() { return exposedHeaders; }
        public void setExposedHeaders(List<String> exposedHeaders) { this.exposedHeaders = exposedHeaders; }
        public Duration getMaxAge() { return maxAge; }
        public void setMaxAge(Duration maxAge) { this.maxAge = maxAge; }
    }

    public static class Risk {
        private int blockThreshold = 71;
        private int throttleThreshold = 31;
        /** Business timezone used for off-hours risk scoring. */
        private String zone = "Asia/Kolkata";

        public int getBlockThreshold() { return blockThreshold; }
        public void setBlockThreshold(int blockThreshold) { this.blockThreshold = blockThreshold; }
        public int getThrottleThreshold() { return throttleThreshold; }
        public void setThrottleThreshold(int throttleThreshold) { this.throttleThreshold = throttleThreshold; }
        public String getZone() { return zone; }
        public void setZone(String zone) { this.zone = zone; }
    }

    public static class RateLimit {
        private boolean enabled = true;
        private Duration window = Duration.ofMinutes(1);
        private int highTrustLimit = 50;
        private int mediumTrustLimit = 30;
        private int lowTrustLimit = 10;
        /** Extra window slots consumed by a request the risk engine throttles. */
        private int throttleCost = 3;
        /** {@code gcra} (default) or {@code sorted-set}. */
        private String algorithm = "gcra";
        /**
         * Coarse per-tenant + per-IP abuse ceiling, independent of the
         * tenant + client quota. It exists so one client cannot hide behind
         * many valid credentials from a single host. It is not a quota:
         * capacity tests must raise it rather than treating it as one.
         */
        private int abuseLimitPerIp = 6_000;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public Duration getWindow() { return window; }
        public void setWindow(Duration window) { this.window = window; }
        public int getHighTrustLimit() { return highTrustLimit; }
        public void setHighTrustLimit(int highTrustLimit) { this.highTrustLimit = highTrustLimit; }
        public int getMediumTrustLimit() { return mediumTrustLimit; }
        public void setMediumTrustLimit(int mediumTrustLimit) { this.mediumTrustLimit = mediumTrustLimit; }
        public int getLowTrustLimit() { return lowTrustLimit; }
        public void setLowTrustLimit(int lowTrustLimit) { this.lowTrustLimit = lowTrustLimit; }
        public int getThrottleCost() { return throttleCost; }
        public void setThrottleCost(int throttleCost) { this.throttleCost = throttleCost; }
        public String getAlgorithm() { return algorithm; }
        public void setAlgorithm(String algorithm) { this.algorithm = algorithm; }
        public int getAbuseLimitPerIp() { return abuseLimitPerIp; }
        public void setAbuseLimitPerIp(int abuseLimitPerIp) { this.abuseLimitPerIp = abuseLimitPerIp; }
    }

    public static class Idempotency {
        private Duration ttl = Duration.ofHours(24);
        private List<String> requiredPaths = List.of("/api/payments");
        private int maxKeyLength = 255;

        public Duration getTtl() { return ttl; }
        public void setTtl(Duration ttl) { this.ttl = ttl; }
        public List<String> getRequiredPaths() { return requiredPaths; }
        public void setRequiredPaths(List<String> requiredPaths) { this.requiredPaths = requiredPaths; }
        public int getMaxKeyLength() { return maxKeyLength; }
        public void setMaxKeyLength(int maxKeyLength) { this.maxKeyLength = maxKeyLength; }

        public boolean isRequiredFor(String path) {
            if (path == null || requiredPaths == null) return false;
            return requiredPaths.stream().anyMatch(prefix ->
                    path.equals(prefix) || path.startsWith(prefix.endsWith("/") ? prefix : prefix + "/"));
        }
    }

    public static class Security {
        private boolean trustProxy;
        private int maxBodyBytes = 1_048_576;
        private String opsApiKey = "";
        private int opsMaxFailedAttempts = 10;
        private Duration opsLockoutWindow = Duration.ofMinutes(15);
        private boolean hstsEnabled = true;

        public boolean isTrustProxy() { return trustProxy; }
        public void setTrustProxy(boolean trustProxy) { this.trustProxy = trustProxy; }
        public int getMaxBodyBytes() { return maxBodyBytes; }
        public void setMaxBodyBytes(int maxBodyBytes) { this.maxBodyBytes = maxBodyBytes; }
        public String getOpsApiKey() { return opsApiKey; }
        public void setOpsApiKey(String opsApiKey) { this.opsApiKey = opsApiKey; }
        public int getOpsMaxFailedAttempts() { return opsMaxFailedAttempts; }
        public void setOpsMaxFailedAttempts(int opsMaxFailedAttempts) { this.opsMaxFailedAttempts = opsMaxFailedAttempts; }
        public Duration getOpsLockoutWindow() { return opsLockoutWindow; }
        public void setOpsLockoutWindow(Duration opsLockoutWindow) { this.opsLockoutWindow = opsLockoutWindow; }
        public boolean isHstsEnabled() { return hstsEnabled; }
        public void setHstsEnabled(boolean hstsEnabled) { this.hstsEnabled = hstsEnabled; }
    }

    public static class Tenant {
        private String header = "X-Tenant-Id";
        private String defaultTenant = "default";
        private String pattern = "^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$";

        public String getHeader() { return header; }
        public void setHeader(String header) { this.header = header; }
        public String getDefaultTenant() { return defaultTenant; }
        public void setDefaultTenant(String defaultTenant) { this.defaultTenant = defaultTenant; }
        public String getPattern() { return pattern; }
        public void setPattern(String pattern) { this.pattern = pattern; }
    }

    public static class Proxy {
        private List<String> forwardHeaders = List.of(
                "Authorization", "Content-Type", "Accept", "Idempotency-Key",
                "X-Tenant-Id", "X-Client-Id", "X-Request-Id");
        private int maxResponseBytes = 10_485_760;

        public List<String> getForwardHeaders() { return forwardHeaders; }
        public void setForwardHeaders(List<String> forwardHeaders) { this.forwardHeaders = forwardHeaders; }
        public int getMaxResponseBytes() { return maxResponseBytes; }
        public void setMaxResponseBytes(int maxResponseBytes) { this.maxResponseBytes = maxResponseBytes; }
    }

    /**
     * OAuth2 client-credentials resource-server settings. The gateway only
     * decodes and validates JWTs; the authorization server issues them.
     * Tenant identity comes from a signed claim, never from a request header.
     */
    public static class Auth {
        private boolean enabled = true;
        /**
         * Issuer URI. When set, the issuer claim is validated. If no JWK set
         * URI is configured, Spring Security discovers the JWK set from the
         * issuer metadata at startup, which requires the authorization server
         * to be reachable during gateway startup.
         */
        private String issuerUri = "";
        /**
         * Direct JWK set URI. Preferred in production: it avoids a startup
         * dependency on authorization-server metadata discovery.
         */
        private String jwkSetUri = "";
        /** Required audience; empty disables audience validation. */
        private String audience = "";
        private String tenantClaim = "tenant_id";
        private String clientIdClaim = "client_id";
        private String scopeClaim = "scope";
        private List<String> requiredScopes = List.of();
        private Duration clockSkew = Duration.ofSeconds(60);

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getIssuerUri() { return issuerUri; }
        public void setIssuerUri(String issuerUri) { this.issuerUri = issuerUri; }
        public String getJwkSetUri() { return jwkSetUri; }
        public void setJwkSetUri(String jwkSetUri) { this.jwkSetUri = jwkSetUri; }
        public String getAudience() { return audience; }
        public void setAudience(String audience) { this.audience = audience; }
        public String getTenantClaim() { return tenantClaim; }
        public void setTenantClaim(String tenantClaim) { this.tenantClaim = tenantClaim; }
        public String getClientIdClaim() { return clientIdClaim; }
        public void setClientIdClaim(String clientIdClaim) { this.clientIdClaim = clientIdClaim; }
        public String getScopeClaim() { return scopeClaim; }
        public void setScopeClaim(String scopeClaim) { this.scopeClaim = scopeClaim; }
        public List<String> getRequiredScopes() { return requiredScopes; }
        public void setRequiredScopes(List<String> requiredScopes) { this.requiredScopes = requiredScopes; }
        public Duration getClockSkew() { return clockSkew; }
        public void setClockSkew(Duration clockSkew) { this.clockSkew = clockSkew; }
    }

    public static class Compliance {
        private Duration defaultWindow = Duration.ofHours(24);
        private Duration maxWindow = Duration.ofDays(90);
        private int defaultRows = 1_000;
        private int maxRows = 10_000;

        public Duration getDefaultWindow() { return defaultWindow; }
        public void setDefaultWindow(Duration defaultWindow) { this.defaultWindow = defaultWindow; }
        public Duration getMaxWindow() { return maxWindow; }
        public void setMaxWindow(Duration maxWindow) { this.maxWindow = maxWindow; }
        public int getDefaultRows() { return defaultRows; }
        public void setDefaultRows(int defaultRows) { this.defaultRows = defaultRows; }
        public int getMaxRows() { return maxRows; }
        public void setMaxRows(int maxRows) { this.maxRows = maxRows; }
    }

    /**
     * Durable audit persistence policy. Sampling only removes clean ALLOW
     * rows; every security, payment, idempotency and operator event is kept at
     * 100%. The effective rate is disclosed by the compliance API.
     */
    public static class Audit {
        /** 1.0 keeps every row; values below 1.0 enable deterministic sampling. */
        private double sampleRate = 1.0;
        private List<String> neverSamplePaths = List.of(
                "/api/payments", "/api/transfers", "/api/transactions", "/api/refunds");
        private int criticalQueueCapacity = 20_000;
        private int sampledQueueCapacity = 10_000;
        private int writeBatchSize = 500;
        private Duration writeFlushInterval = Duration.ofMillis(100);

        public double getSampleRate() { return sampleRate; }
        public void setSampleRate(double sampleRate) { this.sampleRate = sampleRate; }
        public List<String> getNeverSamplePaths() { return neverSamplePaths; }
        public void setNeverSamplePaths(List<String> neverSamplePaths) { this.neverSamplePaths = neverSamplePaths; }
        public int getCriticalQueueCapacity() { return criticalQueueCapacity; }
        public void setCriticalQueueCapacity(int criticalQueueCapacity) { this.criticalQueueCapacity = criticalQueueCapacity; }
        public int getSampledQueueCapacity() { return sampledQueueCapacity; }
        public void setSampledQueueCapacity(int sampledQueueCapacity) { this.sampledQueueCapacity = sampledQueueCapacity; }
        public int getWriteBatchSize() { return writeBatchSize; }
        public void setWriteBatchSize(int writeBatchSize) { this.writeBatchSize = writeBatchSize; }
        public Duration getWriteFlushInterval() { return writeFlushInterval; }
        public void setWriteFlushInterval(Duration writeFlushInterval) { this.writeFlushInterval = writeFlushInterval; }
    }
}
