FROM eclipse-temurin:25-jdk AS build
WORKDIR /app
COPY pom.xml .
COPY mvnw .
COPY .mvn .mvn
COPY pmd-ruleset.xml .
RUN chmod +x mvnw && ./mvnw dependency:go-offline -B
COPY src src
# Default build runs the full suite (CI). The Valkey cache + read-path integration
# tests use Testcontainers (ADR-006), which need a Docker daemon the build container
# lacks — so local Tilt builds override this to `package -DskipTests`. Tests still run in CI.
ARG MAVEN_GOAL="verify"
RUN ./mvnw ${MAVEN_GOAL} -B

FROM eclipse-temurin:25-jre-alpine
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
