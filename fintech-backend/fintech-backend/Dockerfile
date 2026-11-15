# syntax=docker/dockerfile:1.7

# ── Build stage ─────────────────────────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace

COPY pom.xml ./
COPY fintech-gateway/fintech-gateway/pom.xml fintech-gateway/fintech-gateway/pom.xml
COPY fintech-backend/fintech-backend/pom.xml fintech-backend/fintech-backend/pom.xml
COPY fintech-backend/fintech-backend/src fintech-backend/fintech-backend/src

RUN mvn -B -ntp -pl fintech-backend/fintech-backend -am package -DskipTests

# ── Runtime stage ───────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-jammy

RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/* \
 && groupadd --system --gid 1001 fintech \
 && useradd --system --uid 1001 --gid fintech --home-dir /app --shell /usr/sbin/nologin fintech \
 && mkdir -p /app \
 && chown -R fintech:fintech /app

WORKDIR /app
COPY --from=build --chown=fintech:fintech \
    /workspace/fintech-backend/fintech-backend/target/fintech-backend-*.jar /app/app.jar

USER 1001:1001
EXPOSE 8080
ENV TZ=UTC

HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
    CMD curl -fsS http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-XX:+ExitOnOutOfMemoryError", "-jar", "/app/app.jar"]
