FROM maven:3.9.8-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
COPY src ./src
RUN mvn -B -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
# Application jar (artifactId-version pattern; wildcard avoids stale hardcoding)
COPY --from=build /workspace/target/sie-definition-manager-*.jar /app/app.jar
# OTel Java agent jar (copied to target/otel/ at prepare-package; see pom.xml)
COPY --from=build /workspace/target/otel/opentelemetry-javaagent.jar /otel-agent.jar
# Activate the agent at JVM start (AC-1). SDK config comes via env vars
# injected by the Helm Deployment (see ADR-001 Implementation hooks).
ENV JAVA_TOOL_OPTIONS="-javaagent:/otel-agent.jar"
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
