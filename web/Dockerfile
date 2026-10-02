# Portal web client on Red Hat UBI 9: the React build served by nginx, which also proxies /api to
# the API service so the browser sees a single origin. Build context: repository root
# (the client depends on the shared/ workspace).
FROM registry.access.redhat.com/ubi9/nodejs-22:latest AS build
WORKDIR /opt/app-root/src
COPY --chown=1001:0 package.json package-lock.json tsconfig.base.json ./
COPY --chown=1001:0 shared/package.json shared/
COPY --chown=1001:0 web/package.json web/
RUN npm ci --workspace web --include-workspace-root
COPY --chown=1001:0 shared shared
COPY --chown=1001:0 web web
RUN npm run build -w web

FROM registry.access.redhat.com/ubi9/nginx-126:latest
# Where /api is forwarded to (scheme://host:port, no path).
# Set NGINX_ENVSUBST_TEMPLATE_DIR=/etc/nginx/templates-mtls for HTTPS with X.509 client
# certificates (see docker-compose.mtls.yml).
ENV API_URL=http://api:3001 \
    HTTPS_PORT_SUFFIX="" \
    NGINX_ENVSUBST_TEMPLATE_DIR=/etc/nginx/templates
USER 0
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
