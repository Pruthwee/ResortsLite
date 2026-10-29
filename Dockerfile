# Build stage: Maven on Java 8 (the project's Java version)
FROM maven:3.8.6-openjdk-8-slim AS builder
WORKDIR /workspace

# Resolve dependencies first so this layer is cached while only the source changes
COPY pom.xml .
RUN mvn -B dependency:go-offline

COPY src ./src
RUN mvn -B clean package -DskipTests

# Runtime stage
FROM eclipse-temurin:8-jdk-alpine

ENV TZ=UTC \
    LANG=C.UTF-8 \
    LC_ALL=C.UTF-8 \
    SPRING_PROFILES_ACTIVE=default \
    APP_PORT=8080 \
    REPORT_PATH=/app/reports \
    BACKUP_PATH=/app/backups \
    JAVA_OPTS="-Xmx512m -Xms256m -XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -Duser.timezone=UTC -Dfile.encoding=UTF-8 -Dlogging.level.root=INFO"

WORKDIR /app

# Non-root user; the report and backup folders must be writable by it
RUN addgroup -S app && adduser -S app -G app \
    && mkdir -p /app/reports /app/backups \
    && chown -R app:app /app

# spring-boot-maven-plugin repackages a runnable jar, so java -jar is enough
COPY --from=builder /workspace/target/*.jar app.jar
RUN chown app:app app.jar

USER app

EXPOSE 8080

# exec makes java the container's first process so it receives SIGTERM for a graceful shutdown
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
