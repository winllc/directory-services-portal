FROM node:22-alpine AS build
WORKDIR /app
COPY package.json package-lock.json tsconfig.base.json ./
COPY shared/package.json shared/
COPY server/package.json server/
COPY web/package.json web/
RUN npm ci
COPY . .
RUN npm run build

FROM node:22-alpine
WORKDIR /app
ENV NODE_ENV=production STATIC_DIR=/app/web/dist DATA_DIR=/data PORT=3001
COPY --from=build /app/server/dist ./server/dist
COPY --from=build /app/web/dist ./web/dist
COPY --from=build /app/server/package.json ./server/
COPY --from=build /app/node_modules ./node_modules
VOLUME /data
EXPOSE 3001
USER node
CMD ["node", "server/dist/index.js"]
