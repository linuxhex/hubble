# Hubble 部署指南（单 jar）

## 部署方式：只需要一个 jar

jar 打包时已将配置（`.env`）和前端页面一并打入，服务器上**只需要这一个文件**。

### 部署步骤

```bash
# 1. 上传 jar 到服务器（任意目录，如 /opt/hubble/）
scp hubble-server/target/hubble-1.0.0.jar server:/opt/hubble/

# 2. 启动
cd /opt/hubble && nohup java -jar hubble-1.0.0.jar > hubble.log 2>&1 &

# 3. 查看日志
tail -f hubble.log
```

启动后访问 `http://<服务器IP>:18081`。

### 前提

- 服务器已安装 Java 17+（`java -version` 确认）
- 服务器可访问：钉钉 API、Grafana、Doris query-server（内网）、阿里云 SLS

### 更新配置

配置已打入 jar，改配置需要重新打包。如需临时覆盖某个变量，可在启动前用环境变量覆盖（优先级高于 jar 内配置）：

```bash
MCP_BASE_URL=http://新地址 java -jar hubble-1.0.0.jar
```

### 停止 / 重启

```bash
# 停止
pkill -f hubble-1.0.0.jar

# 重启
pkill -f hubble-1.0.0.jar; sleep 2
cd /opt/hubble && nohup java -jar hubble-1.0.0.jar > hubble.log 2>&1 &
```

## 本地打包流程

```bash
# 1. 构建前端
cd hubble-web && npm run build
cp -r dist/* ../hubble-server/src/main/resources/static/

# 2. 确认 hubble-server/src/main/resources/.env 存在（本地配置，不入 git）
# 3. 打包
cd ../hubble-server && mvn clean package -DskipTests

# 产物：target/hubble-1.0.0.jar
```

## 验证

```bash
# 1. 启动日志无 ERROR、无 "URI is not absolute"、无 "undefined scheme"
# 2. 首页可访问
curl -o /dev/null -w "%{http_code}" http://localhost:18081/        # 200
# 3. 登录接口连通
curl "http://localhost:18081/api/auth/dingtalk/login?code=test"
```
