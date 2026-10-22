# Stage 1: Build application JAR
FROM eclipse-temurin:17-jdk-alpine AS builder
WORKDIR /app

COPY gradle/ gradle/
COPY gradlew build.gradle.kts settings.gradle.kts ./
RUN ./gradlew --version --no-daemon

COPY src/ src/
COPY web/ web/
RUN ./gradlew shadowJar --no-daemon || ./gradlew build -x test --no-daemon

# Stage 2: Minimal JRE 17 Runtime
FROM eclipse-temurin:17-jre-alpine AS runtime
WORKDIR /app

RUN addgroup -S appgroup && adduser -S appuser -G appgroup
COPY --from=builder /app/build/libs/*.jar app.jar
RUN chown -R appuser:appgroup /app

USER appuser

EXPOSE 8080

HEALTHCHECK --interval=10s --timeout=3s --start-period=5s --retries=3 \
  CMD wget --no-verbose --tries=1 --spider http://localhost:8080/healthz || exit 1

ENTRYPOINT ["java", "-jar", "app.jar"]
