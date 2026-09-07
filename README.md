# Hubble - 业务监控平台

前后端分离项目，支持独立开发和统一部署。

## 目录结构

```
hubble/
├── hubble-web/       # 前端 (Vue 3 + Vite)
├── hubble-server/    # 后端 (Spring Boot 3 + Java 17)
├── build.sh          # 一键构建脚本
└── README.md
```

## 开发模式

### 启动后端

```bash
cd hubble-server
bash start.sh
```

后端运行在 `http://localhost:8080`

`start.sh` 会自动加载 `.env` 文件中的环境变量。首次运行前需要配置：

```bash
cd hubble-server
cp .env.example .env
# 编辑 .env 填写真实配置
```

### 启动前端

```bash
cd hubble-web
npm install  # 首次需要
npm run dev
```

前端运行在 `http://localhost:82`，API 请求自动代理到后端 8080 端口。

### 访问应用

开发模式访问 `http://localhost:82`

## 生产部署

### 一键构建

```bash
bash build.sh
```

此脚本会：
1. 构建前端，输出到 `hubble-server/src/main/resources/static/`
2. 打包后端 JAR（包含前端静态文件）

### 运行

```bash
java -jar hubble-server/target/hubble-1.0.0.jar
```

应用运行在 `http://localhost:82`（端口可在 `application.yml` 配置）

## 环境要求

- **后端**: Java 17+
- **前端**: Node.js 18+
- **构建**: Maven 3.6+
