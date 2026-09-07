# ---- Stage 1: Build Frontend ----
FROM node:22-alpine AS frontend-build
WORKDIR /build
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
RUN npm run build

# ---- Stage 2: Build Backend ----
FROM maven:3.9-eclipse-temurin-17 AS backend-build
WORKDIR /build
COPY backend/pom.xml ./
RUN mvn dependency:go-offline -B
COPY backend/src ./src
RUN mvn clean package -DskipTests -B

# ---- Stage 3: Runtime ----
FROM eclipse-temurin:17-jre-alpine

RUN apk add --no-cache nginx bash

WORKDIR /app

COPY --from=backend-build /build/target/hubble-1.0.0.jar app.jar
COPY --from=frontend-build /build/dist /usr/share/nginx/html
COPY docker/nginx.conf /etc/nginx/http.d/default.conf
COPY docker/entrypoint.sh /app/entrypoint.sh
RUN chmod +x /app/entrypoint.sh

EXPOSE 80

ENTRYPOINT ["/app/entrypoint.sh"]
