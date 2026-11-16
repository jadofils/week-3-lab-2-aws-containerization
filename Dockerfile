# Onward order application: one image holding the Spring Boot API and the browser UI.
# Built by .github/workflows/build-and-push.yml and run on ECS Fargate (linux/amd64).

# ---- Build stage: compile and package the executable JAR ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
# Download dependencies first so they are cached while only the source changes.
COPY app/pom.xml .
RUN mvn -q -B dependency:go-offline
COPY app/src ./src
# Tests already ran in the workflow's test job; skip them here.
RUN mvn -q -B package -DskipTests

# ---- Runtime stage: JRE only, non-root ----
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN addgroup -S orders && adduser -S -G orders orders
COPY --from=build /build/target/onward-orders.jar /app/onward-orders.jar
USER orders
ENV PORT=8080
EXPOSE 8080
# The ALB checks GET /health; JAVA_TOOL_OPTIONS (heap size) is set in the ECS task definition.
ENTRYPOINT ["java", "-jar", "/app/onward-orders.jar"]
