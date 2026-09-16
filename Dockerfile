FROM eclipse-temurin:21-jre-alpine

RUN apk add --no-cache nginx

COPY docker/nginx.conf /etc/nginx/http.d/default.conf
COPY docker/entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh

COPY hubble-server/target/hubble-1.0.0.jar /app/app.jar

EXPOSE 80 18081

ENTRYPOINT ["/entrypoint.sh"]
