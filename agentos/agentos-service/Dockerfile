# Build from the agentos/ directory. The runtime includes the standard development tools;
# repository-specific toolchains can extend this image.
FROM eclipse-temurin:25-jdk-jammy AS builder
WORKDIR /workspace
COPY . .
RUN --mount=type=cache,target=/root/.gradle chmod +x gradlew && ./gradlew :agentos-service:bootJar deployPlugins --no-daemon
RUN mkdir /out && cp agentos-service/build/libs/agentos-service.jar /out/agentos.jar

FROM node:22-bullseye-slim AS node-toolchain

FROM eclipse-temurin:25-jre-jammy
RUN apt-get update && apt-get install -y --no-install-recommends \
    git bash tmux lsof curl ca-certificates python3 make g++ util-linux \
    && rm -rf /var/lib/apt/lists/*
COPY --from=node-toolchain /usr/local/ /usr/local/
COPY --from=node-toolchain /opt/ /opt/
RUN corepack enable
RUN groupadd -r agentos && useradd -r -m -g agentos agentos
WORKDIR /app
COPY --from=builder --chown=agentos:agentos /out/agentos.jar /app/agentos.jar
COPY --from=builder --chown=agentos:agentos /workspace/plugins/ /app/plugins/
RUN mkdir -p /app/data/exchange && chown -R agentos:agentos /app/data
ENV AGENTOS_EXCHANGE_MOUNT_ROOT=/app/data/exchange
ENV SERVER_PORT=8124
ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -XX:InitialRAMPercentage=50.0"
USER agentos
EXPOSE 8124
HEALTHCHECK --interval=30s --timeout=3s --start-period=60s --retries=3 \
    CMD curl -f "http://localhost:${SERVER_PORT}/management/health" || exit 1
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/agentos.jar"]
