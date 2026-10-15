# Portal API (Spring Boot) on Red Hat UBI 9: the production image. Build context: backend/
#   docker build -t directory-services-portal-api backend
#   docker run -p 3001:3001 -v /path/to/license.lic:/etc/ens/license/license.lic:ro directory-services-portal-api
# Without a license file the API runs its core features; an ENS Solutions license enables data
# analysis, metrics and schema import/export (see "Licensing" in the README).
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
# Set by scripts/package.sh; they replace the base image's own OCI labels.
ARG VERSION=0.1.0
ARG REVISION=unknown
ARG BUILD_DATE=unknown
LABEL org.opencontainers.image.title="Directory Services Portal API" \
      org.opencontainers.image.description="REST API of the Directory Services Portal (web front end for LDAP directories)" \
      org.opencontainers.image.vendor="ENS Solutions" \
      org.opencontainers.image.version="$VERSION" \
      org.opencontainers.image.revision="$REVISION" \
      org.opencontainers.image.created="$BUILD_DATE" \
      org.opencontainers.image.source="https://github.com/winllc/directory-services-portal" \
      org.opencontainers.image.documentation="https://github.com/winllc/directory-services-portal#licensing" \
      com.enssolutions.license.path="/etc/ens/license/license.lic"
# Runs as the image's unprivileged user (uid 185). Directories are group-0 writable so the image
# also works under an arbitrary UID (OpenShift restricted SCC).
USER 0
# Apply the latest Red Hat security and bug-fix updates (UBI minimal: microdnf is its yum).
RUN microdnf -y update && microdnf clean all && rm -rf /var/cache/yum /var/cache/dnf
RUN mkdir -p /app /data && chown 185:0 /data && chmod 0770 /data
# The license is never part of the image. Mount it read-only:
#   -v /path/to/license.lic:/etc/ens/license/license.lic:ro
#   or a directory:  -v /path/to/license-dir:/etc/ens/license:ro
#   or a Docker/Kubernetes secret, with ENS_LICENSE_FILE pointing at it.
ENV ENS_LICENSE_FILE=/etc/ens/license/license.lic
RUN mkdir -p /etc/ens/license && chmod 0755 /etc/ens/license
COPY --from=build /src/target/directory-services-portal.jar /app/app.jar
WORKDIR /app
ENV DATA_DIR=/data PORT=3001 JAVA_OPTS="-XX:MaxRAMPercentage=75.0"
VOLUME /data
EXPOSE 3001
USER 185
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
  CMD curl -sf http://localhost:3001/api/health >/dev/null || exit 1
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar \"$@\"", "--"]
