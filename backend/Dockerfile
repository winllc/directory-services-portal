# Portal API (Spring Boot). Build context: backend/
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN sed -i 's/\r$//' mvnw && chmod +x mvnw && ./mvnw -q -B dependency:go-offline
COPY src src
RUN ./mvnw -q -B package -DskipTests

FROM eclipse-temurin:21-jre
RUN useradd --system --home-dir /app portal && mkdir -p /data && chown portal /data
WORKDIR /app
COPY --from=build /src/target/directory-services-portal.jar app.jar
ENV DATA_DIR=/data PORT=3001
VOLUME /data
EXPOSE 3001
USER portal
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
