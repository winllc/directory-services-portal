# Portal API (Spring Boot) on Red Hat UBI 9. Build context: backend/
FROM registry.access.redhat.com/ubi9/openjdk-21:latest AS build
# The builder image runs as an unprivileged user; the build stage is never shipped.
USER 0
WORKDIR /src
# Uses the image's Red Hat Maven build (the wrapper would download Maven, and the builder image
# has no unzip/gzip to unpack it).
COPY pom.xml ./
RUN mvn -q -B dependency:go-offline
COPY src src
RUN mvn -q -B package -DskipTests

FROM registry.access.redhat.com/ubi9/openjdk-21-runtime:latest
# Runs as the image's unprivileged user (uid 185). Directories are group-0 writable so the image
# also works under an arbitrary UID (OpenShift restricted SCC).
USER 0
RUN mkdir -p /app /data && chown 185:0 /data && chmod 0770 /data
COPY --from=build /src/target/directory-services-portal.jar /app/app.jar
WORKDIR /app
ENV DATA_DIR=/data PORT=3001
VOLUME /data
EXPOSE 3001
USER 185
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]
