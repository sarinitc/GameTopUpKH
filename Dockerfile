# syntax=docker/dockerfile:1

# ---- Build stage -----------------------------------------------------------
# Builds the executable Spring Boot JAR. Tests are not run here: the integration
# tests need Testcontainers/Docker and belong in CI, not in the image build.
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /workspace

COPY pom.xml ./
COPY src ./src

RUN mvn -B -q -DskipTests package \
 && cp target/*.jar /workspace/application.jar

# ---- Runtime stage ---------------------------------------------------------
FROM eclipse-temurin:17-jre-jammy

RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/* \
 && groupadd --system --gid 10001 app \
 && useradd --system --uid 10001 --gid app --home-dir /app --shell /usr/sbin/nologin app

WORKDIR /app
COPY --from=build --chown=10001:10001 /workspace/application.jar /app/application.jar

USER 10001:10001
EXPOSE 8082

# The image runs the production profile by default, so a platform that does not
# set a profile can never boot into `dev` (which enables mock mutation routes).
# Any explicit SPRING_PROFILES_ACTIVE passed by the platform still wins.
ENV SPRING_PROFILES_ACTIVE=prod \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError" \
    TZ=UTC

HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --retries=3 \
    CMD curl --fail --silent --show-error "http://127.0.0.1:${PORT:-8082}/actuator/health/readiness" || exit 1

ENTRYPOINT ["java", "-jar", "/app/application.jar"]
