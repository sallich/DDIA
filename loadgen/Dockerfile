# syntax=docker/dockerfile:1

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml ./
COPY metrics-lib/pom.xml metrics-lib/
COPY service/pom.xml service/
COPY dashboard-generator/pom.xml dashboard-generator/
COPY loadgen/pom.xml loadgen/
COPY loadgen/src loadgen/src
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp -pl loadgen -DskipTests package

FROM eclipse-temurin:21-jre AS runtime
RUN useradd --system --create-home --uid 10003 loadgen
WORKDIR /app
COPY --from=build /build/loadgen/target/loadgen.jar app.jar
USER loadgen
ENTRYPOINT ["java", "-cp", "/app/app.jar", "ru.itmo.ddia.hw1.loadgen.LoadGenerator"]