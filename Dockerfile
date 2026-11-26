# Build stage
FROM maven:3.9.4-eclipse-temurin-8 AS builder
WORKDIR /workspace

# Copy pom.xml first to leverage Docker layer caching
COPY pom.xml .
RUN mvn dependency:go-offline -B

# Copy source code
COPY src ./src

# Build the project
RUN mvn clean package -DskipTests

# Runtime stage
FROM amazoncorretto:8
WORKDIR /app

# Create non-root user
RUN groupadd -r appuser && useradd -r -g appuser appuser

# Copy the built jar
COPY --from=builder /workspace/target/*.jar app.jar

# Create directories for reports and backups
RUN mkdir -p /app/reports /app/backups && chown -R appuser:appuser /app

# Switch to non-root user
USER appuser

# Expose the application port
EXPOSE 8080

# Environment variables
ENV JAVA_OPTS="-Xmx512m -Xms256m -XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0"
ENV TZ=UTC
ENV SPRING_PROFILES_ACTIVE=prod
ENV LANG=en_US.UTF-8
ENV LC_ALL=en_US.UTF-8

# Health check endpoint is /actuator/health (Spring Boot Actuator)
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
