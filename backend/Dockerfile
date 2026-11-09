# =============================================================================
# INSOPHINIA POS Backend — production Docker image
# =============================================================================
# Multi-stage build:
#   1. Maven + JDK 17 build stage (compiles, runs unit tests, packages fat jar)
#   2. JRE 17 runtime stage (only the fat jar + MySQL driver; no Maven cache)
#
# Usage:
#   docker build -f backend/Dockerfile -t insophinia-pos-backend:4.0.0 .
#   docker run --rm -p 8080:8080 --env-file .env insophinia-pos-backend:4.0.0
#
# Pass runtime configuration via environment variables (see .env.example).
# The container does NOT bundle application.yml secrets; everything sensitive
# is read from environment variables at startup.
# =============================================================================

# ---------- build stage ----------
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /workspace
COPY backend/pom.xml ./backend/pom.xml
# Pre-fetch dependencies so subsequent code changes don't re-download everything.
RUN mvn -f backend/pom.xml -B dependency:go-offline
COPY backend/src ./backend/src
RUN mvn -f backend/pom.xml -B -DskipTests clean package

# ---------- runtime stage ----------
FROM eclipse-temurin:17-jre
RUN addgroup --system --gid 1001 insophinia && \
    adduser --system --uid 1001 --gid 1001 --home /app insophinia
WORKDIR /app
COPY --from=build /workspace/backend/target/*-exec.jar /app/app.jar
USER insophinia
EXPOSE 8080
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError -Dfile.encoding=UTF-8 -Duser.timezone=UTC"
ENV SERVER_ADDRESS=0.0.0.0
ENV PORT=8080
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
