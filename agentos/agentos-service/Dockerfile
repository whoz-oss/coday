# Multi-stage Dockerfile for AgentOS Service

# Stage 1: Build stage
FROM eclipse-temurin:17-jdk-jammy AS builder

WORKDIR /workspace

# Copy Gradle wrapper and configuration files
COPY gradlew .
COPY gradle gradle
COPY settings.gradle.kts .
COPY build.gradle.kts .
COPY gradle.properties .

# Copy SDK source code
COPY agentos-sdk agentos-sdk

# Copy Service source code
COPY agentos-service agentos-service

# Make gradlew executable
RUN chmod +x gradlew

# Build the application (this will build SDK first due to dependency)
RUN ./gradlew :agentos-service:build -x test --no-daemon

# Extract the built JAR
RUN mkdir -p /workspace/extracted && \
    cd /workspace/extracted && \
    java -Djarmode=layertools -jar /workspace/agentos-service/build/libs/*.jar extract

# Stage 2: Runtime stage
FROM eclipse-temurin:17-jre-jammy

# Create non-root user for security
RUN groupadd -r agentos && useradd -r -g agentos agentos

# Set working directory
WORKDIR /app

# Copy layers from builder stage (optimized for Docker layer caching)
COPY --from=builder --chown=agentos:agentos /workspace/extracted/dependencies/ ./
COPY --from=builder --chown=agentos:agentos /workspace/extracted/spring-boot-loader/ ./
COPY --from=builder --chown=agentos:agentos /workspace/extracted/snapshot-dependencies/ ./
COPY --from=builder --chown=agentos:agentos /workspace/extracted/application/ ./

# Create plugins directory
RUN mkdir -p /app/plugins && chown -R agentos:agentos /app/plugins

# Switch to non-root user
USER agentos

# Expose application port
EXPOSE 8080

# Health check
HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
    CMD curl -f http://localhost:8123/actuator/health || exit 1

# Set JVM options for containerized environment
ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -XX:InitialRAMPercentage=50.0"

# Run the application
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher"]
