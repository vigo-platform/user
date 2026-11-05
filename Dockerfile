# syntax=docker/dockerfile:1
FROM maven:3.9.6-eclipse-temurin-21 AS builder
WORKDIR /app

COPY pom.xml .
RUN --mount=type=secret,id=maven_settings,target=/root/.m2/settings.xml,required=true \
    mvn dependency:go-offline -B

COPY src ./src
RUN --mount=type=secret,id=maven_settings,target=/root/.m2/settings.xml,required=true \
    mvn clean package -DskipTests

FROM eclipse-temurin:21-jre-jammy AS runtime
LABEL logging="true"
ENV LOGGING_VECTOR_MODE=docker
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/* \
 && groupadd --system vigo \
 && useradd --system --create-home --gid vigo vigo
WORKDIR /app
COPY --from=builder --chown=vigo:vigo /app/target/*.jar app.jar
USER vigo:vigo
EXPOSE 8081 50051
HEALTHCHECK --interval=30s --timeout=4s --start-period=45s --retries=3 CMD curl --fail --silent "http://127.0.0.1:${MANAGEMENT_PORT:-8082}/actuator/health" || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
