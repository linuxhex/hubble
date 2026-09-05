#!/bin/bash
set -e

nginx -g 'daemon off;' &
nginx_pid=$!

java -jar /app/app.jar &
java_pid=$!

trap 'kill $nginx_pid $java_pid 2>/dev/null; exit' TERM INT

wait $java_pid
