#!/bin/bash
# 后端启动脚本 - 自动加载 .env 配置

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# 加载 .env 文件（如果存在）
if [ -f .env ]; then
    echo "✓ 加载 .env 配置..."
    set -a
    source .env
    set +a
else
    echo "⚠ 未找到 .env 文件，复制模板..."
    cp .env.example .env
    echo "✗ 请先编辑 .env 文件填写真实配置，然后重新运行此脚本"
    exit 1
fi

# 检查必需配置
if [ -z "$ALIYUN_SLS_ACCESS_KEY_ID" ] || [ "$ALIYUN_SLS_ACCESS_KEY_ID" = "your-access-key-id" ]; then
    echo "✗ 错误：请先编辑 .env 文件，填写真实的 ALIYUN_SLS_ACCESS_KEY_ID"
    exit 1
fi

if [ -z "$ALIYUN_SLS_PROJECT" ] || [ "$ALIYUN_SLS_PROJECT" = "your-sls-project" ]; then
    echo "✗ 错误：请先编辑 .env 文件，填写真实的 ALIYUN_SLS_PROJECT"
    exit 1
fi

echo "✓ SLS 配置检查通过"
echo "  Project: $ALIYUN_SLS_PROJECT"
echo "  Endpoint: ${ALIYUN_SLS_ENDPOINT:-cn-hangzhou.log.aliyuncs.com}"

# 启动应用
echo ""
echo "启动后端服务..."
mvn spring-boot:run
