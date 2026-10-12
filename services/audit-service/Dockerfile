# Build context is the repository root: docker build -f services/audit-service/Dockerfile .
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
COPY libs/common/pom.xml libs/common/
COPY services/identity-service/pom.xml services/identity-service/
COPY services/api-gateway/pom.xml services/api-gateway/
COPY services/audit-service/pom.xml services/audit-service/
RUN mvn -B -q -pl services/audit-service -am dependency:go-offline -DexcludeReactor=true || true
COPY libs libs
COPY services/audit-service services/audit-service
RUN mvn -B -q -pl services/audit-service -am -DskipTests package

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S app && adduser -S app -G app
WORKDIR /app
COPY --from=build /workspace/services/audit-service/target/audit-service-*.jar app.jar
USER app
EXPOSE 8091
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
HEALTHCHECK --interval=15s --timeout=3s --start-period=60s --retries=5 \
  CMD wget -qO- http://localhost:8091/actuator/health/readiness || exit 1
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
