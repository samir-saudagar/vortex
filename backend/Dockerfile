# syntax=docker/dockerfile:1
FROM eclipse-temurin:21.0.12.1_1-jdk-jammy@sha256:4e61d0b3471137e239397789661f6adeaf8db9adcfae45916b22f628c8a32dc0 AS build
WORKDIR /workspace
COPY gradlew build.gradle.kts settings.gradle.kts gradle.properties ./
COPY gradle gradle
COPY backend backend
RUN java -version && sh ./gradlew --no-daemon :backend:build

FROM eclipse-temurin:21.0.12.1_1-jre-jammy@sha256:9603446cf51296842eab342514857cf7ab55a8891cd564e6c68ec237fae65a16 AS runtime
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 vortex && useradd --uid 10001 --gid vortex --no-create-home vortex
WORKDIR /app
COPY --from=build --chown=10001:10001 /workspace/backend/build/libs/backend.jar /app/vortex.jar
USER 10001:10001
ENV SERVER_ADDRESS=0.0.0.0 SERVER_PORT=8080
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=5s --start-period=60s --retries=12 \
  CMD curl --fail --silent http://127.0.0.1:8080/actuator/health/readiness || exit 1
ENTRYPOINT ["java", "-jar", "/app/vortex.jar"]

