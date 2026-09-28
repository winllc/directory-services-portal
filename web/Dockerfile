# Portal web client: the React build served by nginx, which also proxies /api to the API
# service so the browser sees a single origin. Build context: repository root
# (the client depends on the shared/ workspace).
FROM node:22-alpine AS build
WORKDIR /app
COPY package.json package-lock.json tsconfig.base.json ./
COPY shared/package.json shared/
COPY web/package.json web/
RUN npm ci --workspace web --include-workspace-root
COPY shared shared
COPY web web
RUN npm run build -w web

FROM nginx:1.28-alpine
# Where /api is forwarded to (scheme://host:port, no path).
# Set NGINX_ENVSUBST_TEMPLATE_DIR=/etc/nginx/templates-mtls for HTTPS with X.509 client
# certificates (see docker-compose.mtls.yml).
ENV API_URL=http://api:3001 \
    HTTPS_PORT_SUFFIX="" \
    NGINX_ENTRYPOINT_LOCAL_RESOLVERS=1
COPY web/nginx/templates/ /etc/nginx/templates/
COPY web/nginx/templates-mtls/ /etc/nginx/templates-mtls/
COPY --from=build /app/web/dist /usr/share/nginx/html
EXPOSE 80 443
