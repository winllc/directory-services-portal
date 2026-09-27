# 1) Build the React client
FROM node:22-alpine AS web
WORKDIR /app
COPY package.json package-lock.json tsconfig.base.json ./
COPY shared/package.json shared/
COPY web/package.json web/
RUN npm ci --workspace web --include-workspace-root
COPY shared shared
COPY web web
RUN npm run build -w web

# 2) Build the Spring Boot jar (bundles the client from web/dist)
FROM eclipse-temurin:21-jdk AS api
WORKDIR /app/backend
COPY backend/.mvn .mvn
COPY backend/mvnw backend/pom.xml ./
RUN ./mvnw -q -B dependency:go-offline
COPY backend/src src
COPY --from=web /app/web/dist /app/web/dist
RUN ./mvnw -q -B package -DskipTests

# 3) Runtime
FROM eclipse-temurin:21-jre
RUN useradd --system --home-dir /app portal && mkdir -p /data && chown portal /data
WORKDIR /app
COPY --from=api /app/backend/target/directory-services-portal.jar app.jar
ENV DATA_DIR=/data PORT=3001
VOLUME /data
EXPOSE 3001
USER portal
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
