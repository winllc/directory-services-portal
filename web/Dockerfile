# Portal web client on Red Hat UBI 9: the React build served by nginx, which also proxies /api to
# the API service so the browser sees a single origin. Build context: repository root
# (the client depends on the shared/ workspace).
# Base images are pinned by digest (the tag is for reading); Dependabot proposes new digests.
FROM registry.access.redhat.com/ubi9/nodejs-22:1@sha256:1d423d9a54713c59859c77f35ae7d2e61d330993aa4f28fe979c920038c4c867 AS build
WORKDIR /opt/app-root/src
COPY --chown=1001:0 package.json package-lock.json tsconfig.base.json ./
COPY --chown=1001:0 shared/package.json shared/
COPY --chown=1001:0 web/package.json web/
RUN npm ci --workspace web --include-workspace-root
COPY --chown=1001:0 shared shared
COPY --chown=1001:0 web web
RUN npm run build -w web

FROM registry.access.redhat.com/ubi9/nginx-126:1@sha256:0c3626527f8172f2d54ff67bb25ee286640572ff90a0002bcbe926a9c85ed079
# They replace the base image's own OCI labels (CI adds version and revision).
LABEL org.opencontainers.image.title="Directory Services Portal web" \
      org.opencontainers.image.description="Web client of the Directory Services Portal, served by nginx, which proxies /api to the API" \
      org.opencontainers.image.vendor="ENS Solutions" \
      org.opencontainers.image.source="https://github.com/winllc/directory-services-portal" \
      org.opencontainers.image.documentation="https://github.com/winllc/directory-services-portal#licensing" \
      org.opencontainers.image.licenses="LicenseRef-ENS-Commercial"
# Where /api is forwarded to (scheme://host:port, no path).
# Set NGINX_ENVSUBST_TEMPLATE_DIR=/etc/nginx/templates-mtls for HTTPS with X.509 client
# certificates (see docker-compose.mtls.yml). X.509 sign-in also takes the API's proxy secret
# (X509_PROXY_SECRET_FILE or X509_PROXY_SECRET), X509_HEADER and X509_EDGE_TERMINATION (see
# nginx/entrypoint.sh).
ENV API_URL=http://api:3001 \
    HTTPS_PORT_SUFFIX="" \
    NGINX_ENVSUBST_TEMPLATE_DIR=/etc/nginx/templates
USER 0
# Apply the latest Red Hat security and bug-fix updates.
# PACKAGES_REFRESH changes on every CI build (the run id), so a cached layer never holds back an
# update: without it the build cache would keep whichever packages this step first saw.
ARG PACKAGES_REFRESH=
RUN yum -y update && yum clean all && rm -rf /var/cache/yum /var/cache/dnf
COPY web/nginx/nginx.conf /etc/nginx/nginx.conf
COPY web/nginx/templates/ /etc/nginx/templates/
COPY web/nginx/templates-mtls/ /etc/nginx/templates-mtls/
COPY --chmod=0755 web/nginx/entrypoint.sh /usr/local/bin/dsp-entrypoint.sh
COPY --from=build /opt/app-root/src/web/dist /usr/share/nginx/html
# Rendered configs go to a group-0 writable directory, so the image runs as its unprivileged
# user (uid 1001) or under an arbitrary UID.
RUN mkdir -p /opt/app-root/etc/dsp.d && chown 1001:0 /opt/app-root/etc/dsp.d \
 && chmod 0770 /opt/app-root/etc/dsp.d && chmod -R a+rX /usr/share/nginx/html /etc/nginx
USER 1001
# Unprivileged ports: 8080 (HTTP) and 8443 (HTTPS, mTLS config only).
EXPOSE 8080 8443
ENTRYPOINT ["/usr/local/bin/dsp-entrypoint.sh"]
CMD ["nginx", "-g", "daemon off;"]
