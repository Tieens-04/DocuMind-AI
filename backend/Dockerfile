# syntax=docker/dockerfile:1

FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /workspace

COPY .mvn ./.mvn
COPY mvnw pom.xml ./
RUN sed -i 's/\r$//' mvnw \
    && chmod +x mvnw \
    && ./mvnw -B -ntp dependency:go-offline

COPY src ./src
RUN ./mvnw -B -ntp package -DskipTests \
    && java -Djarmode=tools -jar target/documind-backend-*.jar extract --layers --launcher --destination extracted

FROM eclipse-temurin:21-jre-alpine AS runtime
RUN apk add --no-cache curl \
    && addgroup -S documind \
    && adduser -S -G documind documind

WORKDIR /app

COPY --from=builder --chown=documind:documind /workspace/extracted/dependencies/ ./
COPY --from=builder --chown=documind:documind /workspace/extracted/spring-boot-loader/ ./
COPY --from=builder --chown=documind:documind /workspace/extracted/snapshot-dependencies/ ./
COPY --from=builder --chown=documind:documind /workspace/extracted/application/ ./

USER documind
EXPOSE 8080

ENV SERVER_PORT=8080 \
    JAVA_OPTS="-XX:MaxRAMPercentage=55.0 -XX:+ExitOnOutOfMemoryError"

HEALTHCHECK --interval=10s --timeout=5s --start-period=30s --retries=10 \
    CMD curl -fsS http://127.0.0.1:8080/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "[ -z \"${JWT_SECRET:-}\" ] && unset JWT_SECRET; exec java $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher \"$@\"", "--"]
