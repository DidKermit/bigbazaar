# Build context is the repository root: docker build -f services/identity-service/Dockerfile .
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
COPY libs/common/pom.xml libs/common/
COPY services/identity-service/pom.xml services/identity-service/
COPY services/api-gateway/pom.xml services/api-gateway/
COPY services/audit-service/pom.xml services/audit-service/
RUN mvn -B -q -pl services/identity-service -am dependency:go-offline -DexcludeReactor=true || true
COPY libs libs
COPY services/identity-service services/identity-service
RUN mvn -B -q -pl services/identity-service -am -DskipTests package

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S app && adduser -S app -G app \
    && mkdir -p /data/uploads && chown app:app /data/uploads  # local KYC store; a new volume inherits this owner
WORKDIR /app
COPY --from=build /workspace/services/identity-service/target/identity-service-*.jar app.jar
USER app
EXPOSE 8081
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
HEALTHCHECK --interval=15s --timeout=3s --start-period=60s --retries=5 \
  CMD wget -qO- http://localhost:8081/actuator/health/readiness || exit 1
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
