# syntax=docker/dockerfile:1

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml ./
COPY metrics-lib/pom.xml metrics-lib/
COPY service/pom.xml service/
COPY dashboard-generator/pom.xml dashboard-generator/
COPY loadgen/pom.xml loadgen/
COPY dashboard-generator/src dashboard-generator/src
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp -pl dashboard-generator -DskipTests package

FROM eclipse-temurin:21-jre AS runtime
RUN useradd --system --create-home --uid 10002 generator
# The directory for the shared token volume is created up front with the right owner:
# Docker initialises an empty named volume from the image content and permissions,
# otherwise the mount point belongs to root while the container runs as another user.
RUN mkdir -p /run/grafana-token && chown generator:generator /run/grafana-token
WORKDIR /app
COPY --from=build /build/dashboard-generator/target/dashboard-generator.jar app.jar
USER generator
# The entry point is selected in docker-compose.yaml: the image holds two main classes.
ENTRYPOINT ["java", "-cp", "/app/app.jar"]