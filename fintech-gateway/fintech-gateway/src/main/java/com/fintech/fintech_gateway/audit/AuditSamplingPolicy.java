package com.fintech.fintech_gateway.audit;

import com.fintech.fintech_gateway.audit.AuditLogger.AuditEvent;
import com.fintech.fintech_gateway.config.GatewayProperties;
import com.fintech.fintech_gateway.risk.RiskDecisionEngine.Decision;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.List;

/**
 * Decides which audit events reach the durable evidence store.
 *
 * <p>Sampling is deterministic on the request id within one process: the same
 * request always lands on the same side of the sample. The digest is salted
 * with a random per-process value, so a caller who chooses {@code X-Request-Id}
 * cannot predict, precompute or steer the selection. Only clean ALLOW traffic
 * is eligible. Security blocks, authentication failures, attack detections,
 * payment paths and any idempotent request are always persisted. A blank
 * request id is also always persisted, because there is nothing stable to
 * sample on.</p>
 *
 * <p>At a rate of 1.0 no sampling is active and every event is written to the
 * critical dataset, keeping the previous 100% behaviour exactly.</p>
 */
@Component
public class AuditSamplingPolicy {

    private static final Logger log = LoggerFactory.getLogger(AuditSamplingPolicy.class);

    /** A near-zero rate is indistinguishable from disabling audit entirely. */
    static final double PROD_MIN_SAMPLE_RATE = 0.01;

    private static final int SAMPLE_SALT_BYTES = 32;

    public enum Persistence {
        /** Always kept: security, payment, idempotency or operator event. */
        PERSIST_CRITICAL,
        /** Clean traffic selected by the sample; written in the sampled batches. */
        PERSIST_SAMPLED,
        /** Clean traffic outside the sample; only aggregate metrics remain. */
        SKIP
    }

    private static final ThreadLocal<MessageDigest> SHA256 =
            ThreadLocal.withInitial(AuditSamplingPolicy::newDigest);

    private final double sampleRate;
    private final boolean sampled;
    private final List<String> neverSamplePaths;
    private final byte[] sampleSalt;

    /**
     * Production constructor. Spring must be told which of the two
     * constructors to use; the salt-injected one exists only as a test seam so
     * a test can assert selection deterministically.
     */
    @Autowired
    public AuditSamplingPolicy(GatewayProperties properties, Environment environment) {
        this(properties, environment, randomSalt());
    }

    AuditSamplingPolicy(GatewayProperties properties, Environment environment, byte[] sampleSalt) {
        GatewayProperties.Audit audit = properties.getAudit();
        double configured = audit.getSampleRate();

        if (!Double.isFinite(configured) || configured < 0.0 || configured > 1.0) {
            throw new IllegalArgumentException(
                    "gateway.audit.sample-rate must be between 0.0 and 1.0, got " + configured);
        }
        if (environment.matchesProfiles("prod") && configured < PROD_MIN_SAMPLE_RATE) {
            throw new IllegalArgumentException(
                    "gateway.audit.sample-rate must be at least " + PROD_MIN_SAMPLE_RATE
                            + " in the prod profile; " + configured
                            + " is indistinguishable from disabling audit");
        }

        this.sampleRate = configured;
        this.sampled = configured < 1.0;
        this.neverSamplePaths = audit.getNeverSamplePaths() == null
                ? List.of()
                : List.copyOf(audit.getNeverSamplePaths());
        this.sampleSalt = sampleSalt.clone();
    }

    @PostConstruct
    void logEffectivePolicy() {
        if (sampled) {
            log.info("Audit persistence sampling is ACTIVE: sampleRate={} "
                            + "(never-sample paths: {}). Compliance exports disclose this.",
                    sampleRate, neverSamplePaths);
        } else {
            log.info("Audit persistence sampling is disabled: every event is persisted "
                    + "(sampleRate=1.0)");
        }
    }

    public Persistence decide(AuditEvent event) {
        if (alwaysPersist(event)) {
            return Persistence.PERSIST_CRITICAL;
        }
        if (!sampled) {
            return Persistence.PERSIST_CRITICAL;
        }
        if (event.getRequestId() == null || event.getRequestId().isBlank()) {
            return Persistence.PERSIST_CRITICAL;
        }
        return inSample(event.getRequestId())
                ? Persistence.PERSIST_SAMPLED
                : Persistence.SKIP;
    }

    public boolean isSampled() {
        return sampled;
    }

    public double getSampleRate() {
        return sampleRate;
    }

    private boolean alwaysPersist(AuditEvent event) {
        if (event.getDecision() != Decision.ALLOW) {
            return true;
        }
        if (event.getStatusCode() >= 400) {
            return true;
        }
        if (event.getAttackType() != null && !event.getAttackType().isBlank()) {
            return true;
        }
        if (event.isIdempotencyInvolved()) {
            return true;
        }
        // A percent-encoded path may route to a payment endpoint after the
        // servlet container decodes it. It is cheap to keep the row and
        // unacceptable to sample payment evidence by accident.
        if (event.getUri() != null && event.getUri().indexOf('%') >= 0) {
            return true;
        }
        return isNeverSamplePath(AuditLogger.sanitize(event.getUri()));
    }

    private boolean isNeverSamplePath(String uri) {
        if (uri == null || uri.isEmpty()) {
            return false;
        }
        for (String configured : neverSamplePaths) {
            if (configured == null || configured.isBlank()) {
                continue;
            }
            String prefix = configured.endsWith("/")
                    ? configured.substring(0, configured.length() - 1)
                    : configured;
            if (uri.equals(prefix) || uri.startsWith(prefix + "/")) {
                return true;
            }
        }
        return false;
    }

    private boolean inSample(String requestId) {
        MessageDigest digest = SHA256.get();
        digest.reset();
        digest.update(sampleSalt);
        byte[] hash = digest.digest(requestId.getBytes(StandardCharsets.UTF_8));

        long value = 0;
        for (int i = 0; i < Long.BYTES; i++) {
            value = (value << 8) | (hash[i] & 0xFFL);
        }
        // Uniform in [0, 1): keep 53 bits, the exact mantissa width of a double.
        double bucket = (value >>> 11) * 0x1.0p-53;
        return bucket < sampleRate;
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the JVM", e);
        }
    }

    private static byte[] randomSalt() {
        byte[] salt = new byte[SAMPLE_SALT_BYTES];
        new SecureRandom().nextBytes(salt);
        return salt;
    }
}
