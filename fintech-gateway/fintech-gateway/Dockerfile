# syntax=docker/dockerfile:1.7

# ── Build stage ─────────────────────────────────────────────────────────────
# The reactor root lists both modules, so the build context is the repository
# root even though only the gateway is packaged here.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace

COPY pom.xml ./
COPY fintech-gateway/fintech-gateway/pom.xml fintech-gateway/fintech-gateway/pom.xml
COPY fintech-backend/fintech-backend/pom.xml fintech-backend/fintech-backend/pom.xml
COPY fintech-gateway/fintech-gateway/src fintech-gateway/fintech-gateway/src

RUN mvn -B -ntp -pl fintech-gateway/fintech-gateway -am package -DskipTests

# ── Runtime stage ───────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-jammy

RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/* \
 && groupadd --system --gid 1001 fintech \
 && useradd --system --uid 1001 --gid fintech --home-dir /app --shell /usr/sbin/nologin fintech \
 && mkdir -p /app /var/log/fintech \
 && chown -R fintech:fintech /app /var/log/fintech

WORKDIR /app
COPY --from=build --chown=fintech:fintech \
    /workspace/fintech-gateway/fintech-gateway/target/fintech-gateway-*.jar /app/app.jar

USER 1001:1001
EXPOSE 8081

# Audit and application logs are written here. Mount a volume in production,
# or set LOG_DIR to a path collected by your log shipper.
ENV LOG_DIR=/var/log/fintech \
    TZ=UTC

HEALTHCHECK --interval=30s --timeout=5s --start-period=45s --retries=3 \
    CMD curl -fsS http://localhost:8081/actuator/health/liveness || exit 1

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-XX:+ExitOnOutOfMemoryError", "-jar", "/app/app.jar"]
