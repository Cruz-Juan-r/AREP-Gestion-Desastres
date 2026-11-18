# Edge node (Spring Boot). Build context: the repository root (multi-module Maven).
#   docker build -f edge-node/Dockerfile .
# Unit tests run with ./mvnw verify on the host (one of them needs Python), not here.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY mvnw pom.xml ./
COPY .mvn .mvn
COPY shared shared
COPY edge-node edge-node
COPY cloud-api/pom.xml cloud-api/pom.xml
RUN --mount=type=cache,target=/root/.m2 \
    sed -i 's/\r$//' mvnw && chmod +x mvnw && \
    ./mvnw -B -q -pl edge-node -am package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/edge-node/target/edge-node.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
