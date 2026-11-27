# syntax=docker/dockerfile:1
# ---- Build stage: Maven on Java 8 ----
FROM maven:3.8.6-openjdk-8-slim AS builder
WORKDIR /workspace

# Resolve dependencies first so they are cached between builds
COPY pom.xml .
RUN mvn -B dependency:go-offline

COPY src ./src
RUN mvn -B clean package -DskipTests

# ---- Runtime stage ----
FROM amazoncorretto:8

ENV TZ=UTC \
    LANG=en_US.UTF-8 \
    LC_ALL=en_US.UTF-8 \
    JAVA_TOOL_OPTIONS="-Dfile.encoding=UTF-8" \
    JAVA_OPTS="-Xmx512m -Xms256m -XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0" \
    SPRING_PROFILES_ACTIVE=prod \
    LOGGING_LEVEL_ROOT=INFO \
    APP_PORT=8080 \
    REPORT_PATH=/app/reports \
    BACKUP_PATH=/app/backups/nightly

WORKDIR /app

RUN groupadd -r -g 1001 app && useradd -r -u 1001 -g app app \
    && mkdir -p /app/reports /app/backups/nightly \
    && chown -R app:app /app

COPY --from=builder --chown=app:app /workspace/target/*.jar app.jar

USER 1001

EXPOSE 8080

# exec so that java is PID 1 and receives SIGTERM for a graceful shutdown
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
