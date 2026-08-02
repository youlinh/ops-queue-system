FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY deploy/maven-settings.xml settings.xml
COPY backend/pom.xml .
# Cached layer: source edits no longer re-download every dependency.
RUN mvn -q -s settings.xml -DskipTests dependency:go-offline
COPY backend/src ./src
RUN mvn -s settings.xml -DskipTests package

FROM eclipse-temurin:21-jre-jammy
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /src/target/*.jar /app/app.jar
USER 10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
