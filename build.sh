#!/bin/bash
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

echo "===== 1/2 构建前端 ====="
cd "$SCRIPT_DIR/hubble-web"
npm run build
echo "前端构建完成 → hubble-server/src/main/resources/static/"

echo ""
echo "===== 2/2 打包后端 JAR ====="
cd "$SCRIPT_DIR/hubble-server"
mvn package -DskipTests -q

JAR=$(ls target/hubble-*.jar 2>/dev/null | head -1)
echo ""
echo "===== 打包完成 ====="
echo "JAR 路径: hubble-server/$JAR"
echo "运行命令: java -jar hubble-server/$JAR"
