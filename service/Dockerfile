# syntax=docker/dockerfile:1

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
# Poms first: the dependency layer is reused between builds.
COPY pom.xml ./
COPY metrics-lib/pom.xml metrics-lib/
COPY service/pom.xml service/
# Needed even though the service does not depend on them: the modules are declared in the
# parent pom, and without their pom.xml the Maven reactor does not resolve.
COPY dashboard-generator/pom.xml dashboard-generator/
COPY loadgen/pom.xml loadgen/
COPY metrics-lib/src metrics-lib/src
COPY service/src service/src
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp -pl service -am -DskipTests package

FROM eclipse-temurin:21-jre AS runtime
# curl is required by the docker-compose health probe
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
RUN useradd --system --create-home --uid 10001 app
WORKDIR /app
COPY --from=build /build/service/target/service.jar app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-XX:+ExitOnOutOfMemoryError", "-jar", "/app/app.jar"]
