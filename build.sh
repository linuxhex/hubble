#!/bin/bash
set -e

PROJECT_ROOT="$(cd "$(dirname "$0")" && pwd)"
BACKEND_DIR="$PROJECT_ROOT/backend"
STATIC_DIR="$BACKEND_DIR/src/main/resources/static"

echo "═══════════════════════════════════════════"
echo "  Hubble 一键构建（前后端合并 jar）"
echo "═══════════════════════════════════════════"

# 1. 构建前端
echo ""
echo "[1/3] 构建前端..."
cd "$PROJECT_ROOT"
npm ci
npm run build

# 2. 拷贝前端产物到后端 static 目录
echo ""
echo "[2/3] 拷贝前端产物到 backend/src/main/resources/static/..."
rm -rf "$STATIC_DIR"
mkdir -p "$STATIC_DIR"
cp -r "$PROJECT_ROOT/dist/"* "$STATIC_DIR/"

# 3. 构建后端 jar
echo ""
echo "[3/3] 构建后端 jar..."
cd "$BACKEND_DIR"
mvn clean package -DskipTests -B

JAR_FILE="$BACKEND_DIR/target/hubble-1.0.0.jar"

echo ""
echo "═══════════════════════════════════════════"
echo "  构建完成！"
echo "═══════════════════════════════════════════"
echo ""
echo "  JAR: $JAR_FILE"
echo ""
echo "  运行方式："
echo "    java -jar $JAR_FILE"
echo ""
echo "  访问："
echo "    前端页面: http://<服务器IP>:8080/"
echo "    后端 API: http://<服务器IP>:8080/api/..."
echo ""
