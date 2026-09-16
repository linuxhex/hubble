# Hubble 部署环境变量清单

## 必需环境变量（服务器启动前必须设置）

### 钉钉登录
```bash
export DINGTALK_APP_KEY=YOUR_DINGTALK_APP_KEY
export DINGTALK_APP_SECRET=YOUR_DINGTALK_APP_SECRET
export DINGTALK_REDIRECT_URL=http://<服务器IP>:18081/login
```
**注意**: `DINGTALK_REDIRECT_URL` 必须与钉钉开放平台后台配置的回调地址完全一致

### 阿里云 SLS/ARMS（网关日志、链路追踪）
```bash
export ALIYUN_SLS_ACCESS_KEY_ID=YOUR_ACCESS_KEY_ID
export ALIYUN_SLS_ACCESS_KEY_SECRET=YOUR_ACCESS_KEY_SECRET
export ALIYUN_SLS_PROJECT=YOUR_SLS_PROJECT
export ALIYUN_SLS_ENDPOINT=cn-hangzhou.log.aliyuncs.com
export ARMS_AK_ID=YOUR_ACCESS_KEY_ID
export ARMS_AK_SECRET=YOUR_ACCESS_KEY_SECRET
export ARMS_REGION=cn-hangzhou
```

### Grafana（中间件监控、服务负载）
```bash
export GRAFANA_URL=https://graf.ykccn.net
export GRAFANA_USER=caomunian
export GRAFANA_PASS=YOUR_GRAFANA_PASS
export GRAFANA_DS_UID=6A__NzsMk
export GRAFANA_NODE_DS_UID=cem0jt0mij668b
export GRAFANA_ALIYUN_DS_UID=l9II0lm4z
export GRAFANA_BIZ_DS_UID=Ufpny5tSz
```

### Doris 数仓（业务监控）
```bash
export MCP_BASE_URL=http://10.20.0.2:8081
export MCP_USER=caomunian
export MCP_PASS=YOUR_MCP_PASS
```

### JWT 认证
```bash
export JWT_PUBLIC_KEY=CloudEyesJwtSecretKeyForHMACSHA256AlgorithmMustBeAtLeast32Bytes
```

### 业务监控权限
```bash
export BIZ_ANALYSIS_USERS=lianzi
```

### 钉钉机器人（告警通知）
```bash
export DINGTALK_ROBOT_WEBHOOK=https://oapi.dingtalk.com/robot/send?access_token=xxx
export DINGTALK_ROBOT_SECRET=SECxxx
```

### 服务负载配置
```bash
export SERVICE_LOAD_APP_MAP=order-server:OtsOrderServer,base-server:CTP-BASE-SERVER,...
export SERVICE_LOAD_FALLBACK_SERVICES=order-server,base-server,...
export GATEWAY_CORE_SERVICES=guan-zhong,order-server,...
export GATEWAY_KNOWN_SERVICES=orderserver,DeviceBusinessServer,...
export TRACE_GATEWAY_CONTAINER=guan-zhong
export TRACE_FALLBACK_SERVICES=order-server,finance-server,...
```

## 启动命令

```bash
# 方式1：直接 export 后启动
export MCP_BASE_URL=http://10.20.0.2:8081
# ... 其他环境变量 ...
java -jar hubble-1.0.0.jar

# 方式2：使用 env 文件（推荐）
# 创建 server.env 文件（不要提交到 git）
cat > server.env << 'EOF'
MCP_BASE_URL=http://10.20.0.2:8081
MCP_USER=caomunian
# ... 其他变量 ...
EOF

# 加载并启动
set -a && source server.env && set +a && java -jar hubble-1.0.0.jar

# 或使用项目自带启动脚本（推荐）
cp .env.example .env && vi .env   # 填入实际配置
./start.sh start
```

## 网络要求

服务器需要能访问以下外部服务：
- `https://api.dingtalk.com` — 钉钉登录 API
- `https://graf.ykccn.net` — Grafana 监控
- `http://10.20.0.2:8081` — Doris query-server（内网）
- `cn-hangzhou.log.aliyuncs.com` — 阿里云 SLS

## 验证

启动后检查：
```bash
# 1. 检查启动日志
tail -f logs/hubble.log

# 2. 测试钉钉登录
curl -X POST "http://localhost:18081/api/auth/dingtalk/login?code=test"

# 3. 测试业务监控
curl http://localhost:18081/api/biz-analysis/overview

# 4. 测试中间件监控
curl http://localhost:18081/api/middleware/redis
```
