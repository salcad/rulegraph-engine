# Build stage: compile and package the engine.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B -DskipTests package

# Run stage: a slim JRE with the jar and the sample materials.
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /build/target/rulegraph-engine-0.1.0.jar app.jar
COPY sample_docs ./sample_docs
EXPOSE 8074
# No command argument: the application runs as the web API server.
ENTRYPOINT ["java", "-jar", "app.jar"]
