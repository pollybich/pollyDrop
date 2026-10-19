# syntax=docker/dockerfile:1
FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml ./
COPY src ./src
# Optional build secrets support enterprise/cloud proxies without retaining their configuration.
RUN --mount=type=cache,target=/root/.m2 \
    --mount=type=secret,id=maven_settings \
    --mount=type=secret,id=java_truststore \
    set -eu; \
    if [ -f /run/secrets/java_truststore ]; then export MAVEN_OPTS="-Djavax.net.ssl.trustStore=/run/secrets/java_truststore"; fi; \
    if [ -f /run/secrets/maven_settings ]; then mvn -B -s /run/secrets/maven_settings package -DskipTests; else mvn -B package -DskipTests; fi
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S app && adduser -S app -G app && mkdir -p /data/files && chown -R app:app /data
WORKDIR /app
COPY --from=build /app/target/pollydrop-0.1.0-beta.4.jar app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70", "-jar", "app.jar"]
