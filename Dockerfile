# syntax=docker/dockerfile:1.7
# Required named build context: --build-context contracts=../api-contracts
FROM eclipse-temurin:21-jdk-alpine AS builder
RUN apk add --no-cache python3 curl
WORKDIR /contracts
COPY --from=contracts /grpc/ ./grpc/
COPY --from=contracts /scripts/grpc_release.py ./scripts/grpc_release.py
COPY --from=contracts /build-support/grpc-pom.xml ./build-support/grpc-pom.xml
COPY --from=contracts /mvnw ./mvnw
COPY --from=contracts /.mvn/wrapper/maven-wrapper.properties ./.mvn/wrapper/maven-wrapper.properties
RUN chmod +x mvnw && python3 -I scripts/grpc_release.py prepare --contract users --version 3.1.0 \
 && ./mvnw -B -ntp -f target/grpc/users/pom.xml -DskipTests install
WORKDIR /build
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY src/ src/
RUN chmod +x mvnw && ./mvnw -B -ntp -DskipTests package

FROM eclipse-temurin:21-jre-alpine AS runtime
LABEL logging="true"
ENV LOGGING_VECTOR_MODE=docker
RUN apk add --no-cache curl && addgroup -S vigo && adduser -S -G vigo vigo
WORKDIR /app
COPY --from=builder --chown=vigo:vigo /build/target/user-0.0.1-SNAPSHOT.jar /app/app.jar
USER vigo:vigo
EXPOSE 8081 50051
HEALTHCHECK --interval=30s --timeout=4s --start-period=45s --retries=3 CMD curl --fail --silent http://127.0.0.1:9081/actuator/health || exit 1
ENTRYPOINT ["java","-jar","/app/app.jar"]
