# Portal API (Spring Boot) on Red Hat UBI 9: the production image. Build context: backend/
#   docker build -t directory-services-portal-api backend
#   docker run -p 3001:3001 -v dsp-data:/data -v dsp-license:/var/lib/ens/directory-portal directory-services-portal-api
# Without an ENS Solutions license only sign-in and the license screen work: an administrator
# uploads the license there (see "Licensing" in the README).
# Base images are pinned by digest (the tag is for reading); Dependabot proposes new digests.
FROM registry.access.redhat.com/ubi9/openjdk-21:1.24@sha256:66175ec6c53f655a24c1c3f81c52b3e281c6918c917435b1014eb75cd11fbe75 AS build
# The builder image runs as an unprivileged user; the build stage is never shipped.
USER 0
WORKDIR /src
# Uses the image's Red Hat Maven build (the wrapper would download Maven, and the builder image
# has no unzip/gzip to unpack it).
COPY pom.xml ./
RUN mvn -q -B dependency:go-offline
COPY src src
RUN mvn -q -B package -DskipTests

FROM registry.access.redhat.com/ubi9/openjdk-21-runtime:1.24@sha256:856ee6542b8fdf09a065fd5439309fc3f3c821fb8efac48aa6191c93635dd907
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
      org.opencontainers.image.licenses="LicenseRef-ENS-Commercial" \
      com.enssolutions.license.path="/var/lib/ens/directory-portal/license.lic"
# Runs as the image's unprivileged user (uid 185). Directories are group-0 writable so the image
# also works under an arbitrary UID (OpenShift restricted SCC).
USER 0
# Apply the latest Red Hat security and bug-fix updates (UBI minimal: microdnf is its yum).
# PACKAGES_REFRESH changes on every CI build (the run id), so a cached layer never holds back an
# update: without it the build cache would keep whichever packages this step first saw.
ARG PACKAGES_REFRESH=
RUN microdnf -y update && microdnf clean all && rm -rf /var/cache/yum /var/cache/dnf
RUN mkdir -p /app /data && chown 185:0 /data && chmod 0770 /data
# The ENS license is never part of the image. It lives in one persistent, writable directory: the
# license screen writes uploads there, and it holds this installation's id (customers bind
# licenses to it) and the license revisions seen. Keep it on a volume across upgrades.
ENV ENS_LICENSE_STATE_DIR=/var/lib/ens/directory-portal \
    ENS_LICENSE_FILE=/var/lib/ens/directory-portal/license.lic
RUN mkdir -p /var/lib/ens/directory-portal && chown -R 185:0 /var/lib/ens && chmod -R 0770 /var/lib/ens
# Audit file output (ENS_AUDIT_FILE, off unless set; e.g. /var/log/ens/directory-portal/audit.jsonl):
# mount the ens-audit volume here. The audit store itself is under /data/audit.
RUN mkdir -p /var/log/ens/directory-portal && chown -R 185:0 /var/log/ens && chmod -R 0770 /var/log/ens
COPY --from=build /src/target/directory-services-portal.jar /app/app.jar
WORKDIR /app
ENV DATA_DIR=/data PORT=3001 JAVA_OPTS="-XX:MaxRAMPercentage=75.0"
VOLUME /data /var/lib/ens
EXPOSE 3001
USER 185
# Readiness: the directory answers and the audit log is writable (on the main port also with
# MANAGEMENT_SERVER_PORT set).
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
  CMD curl -sf "http://localhost:${PORT:-3001}/readyz" >/dev/null || exit 1
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar \"$@\"", "--"]
