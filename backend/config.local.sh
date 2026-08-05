#!/bin/bash
# 阿里云 SLS 配置 - 本地配置文件
# 使用方法：source config.local.sh

# 必填：阿里云 AccessKey
export ALIYUN_SLS_ACCESS_KEY_ID="your-access-key-id"
export ALIYUN_SLS_ACCESS_KEY_SECRET="your-access-key-secret"

# 必填：SLS Project 名称
export ALIYUN_SLS_PROJECT="your-sls-project"

# 可选：SLS Endpoint（默认 cn-hangzhou.log.aliyuncs.com）
export ALIYUN_SLS_ENDPOINT="cn-hangzhou.log.aliyuncs.com"

# 可选：SLS Logstore（默认 all）
export ALIYUN_SLS_LOGSTORE="all"

echo "✓ SLS 配置已加载"
echo "  Project: $ALIYUN_SLS_PROJECT"
echo "  Endpoint: $ALIYUN_SLS_ENDPOINT"
