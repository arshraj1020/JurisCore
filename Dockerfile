# syntax=docker/dockerfile:1
# =============================================================================
# JurisCore container image
#
# Two stages: a fat build image that never ships, and a JRE-only runtime.
#
# Dependency caching uses a BuildKit cache mount on ~/.m2 rather than the usual
# "copy the poms, run dependency:go-offline" trick. That trick is unreliable in a
# multi-module reactor: go-offline resolves the whole dependency graph, which
# includes the sibling modules that have not been built at that layer, and fails.
# A cache mount survives across builds, needs no ordering games, and keeps the
# downloaded repository out of the final image entirely.
# =============================================================================

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

COPY pom.xml ./
COPY juriscore-common/pom.xml juriscore-common/
COPY juriscore-organization/pom.xml juriscore-organization/
COPY juriscore-identity/pom.xml juriscore-identity/
COPY juriscore-app/pom.xml juriscore-app/
COPY juriscore-common/src juriscore-common/src
COPY juriscore-organization/src juriscore-organization/src
COPY juriscore-identity/src juriscore-identity/src
COPY juriscore-app/src juriscore-app/src

# Tests need Docker (Testcontainers) and run in CI, not inside an image build.
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp -DskipTests package


FROM eclipse-temurin:21-jre-jammy AS runtime

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# Never run as root: a container escape should not start with uid 0.
RUN groupadd --system --gid 1001 juriscore \
    && useradd --system --uid 1001 --gid juriscore --home /app juriscore

WORKDIR /app
COPY --from=build --chown=juriscore:juriscore /build/juriscore-app/target/juriscore.jar app.jar

USER juriscore
EXPOSE 8080

# Let the JVM size its heap from the container's cgroup limit rather than the host's RAM.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError -Djava.security.egd=file:/dev/./urandom"
ENV SPRING_PROFILES_ACTIVE=docker

HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD curl -fsS http://localhost:8080/actuator/health/readiness || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
