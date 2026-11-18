# National cloud API (Spring Boot). Build context: the prototipo-edge/ root (multi-module Maven).
#   docker build -f cloud-api/Dockerfile .
# Unit tests run with ./mvnw verify on the host, not here.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY mvnw pom.xml ./
COPY .mvn .mvn
COPY shared shared
COPY cloud-api cloud-api
COPY edge-node/pom.xml edge-node/pom.xml
RUN --mount=type=cache,target=/root/.m2 \
    sed -i 's/\r$//' mvnw && chmod +x mvnw && \
    ./mvnw -B -q -pl cloud-api -am package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/cloud-api/target/cloud-api.jar app.jar
EXPOSE 8000
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
