# Hubble - 业务监控平台

前后端分离项目，支持独立开发和统一部署。

## 目录结构

```
hubble/
├── hubble-web/          # 前端 (Vue 3 + Vite)
├── hubble-server/       # 后端 (Spring Boot 3 + Java 17)
├── docs/
│   ├── requirements/    # 需求文档 + 测试截图归档（见下方说明）
│   └── test-reports/    # 综合测试报告
├── build.sh             # 一键构建脚本
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

---

## 运行截屏记账

每个功能开发或 bug 修复完成后，通过自动化测试截图留存，归档到 `docs/requirements/` 目录，作为功能验收凭证。

### 目录规范

```
docs/requirements/
└── {功能名称}/              # 如 service-load、ai-chat、hubble-alert-test
    ├── analysis.md          # 需求分析（可选）
    ├── changes.md           # 改动说明（可选）
    ├── test-report.md       # 测试报告（必须）
    └── screenshots/         # 截图目录
        ├── 场景1-页面加载.png
        ├── 场景2-切换服务.png
        └── ...
```

### 截图命名规范

```
场景{序号}-{操作描述}.png
```

示例：
- `场景1-页面加载.png`
- `场景2-切换服务-base-server.png`
- `场景3-图表数据渲染.png`
- `修复后-经营分析页面.png`（bug 修复场景用"修复前/修复后"前缀）

### 测试报告格式

`test-report.md` 按以下结构编写：

```markdown
# {功能名称}测试报告

## 概要
- 测试时间：YYYY-MM-DD HH:mm
- 项目类型：Web / 小程序
- 测试环境：http://localhost:xxxx
- 驱动引擎：ego-browser / miniprogram-automator
- 场景总数：N
- 通过：N
- 失败：0
- 通过率：100%

## 详细结果

### 场景 1：{场景名称} ✓
| 步骤 | 操作 | 结果 | 截图 |
|------|------|------|------|
| 1    | 打开 /xxx 页面 | ✓ | |
| 2    | 验证某元素存在 | ✓ | screenshots/场景1-页面加载.png |

## 结论

{功能验收结论}
```

### 执行流程

```
开发完成
  ↓
启动本地服务（前端 + 后端）
  ↓
调用 cwork-test 跑自动化测试
  ↓
自动生成截图 → 保存到 docs/requirements/{功能名称}/screenshots/
  ↓
自动生成 test-report.md
  ↓
截图 + 报告一并提交到 git
```

### 注意事项

- 截图只记录**关键场景**，不需要每一步都截图，在关键验证节点截一张即可
- 根目录的临时截图（如调试截图）**不要提交**，统一归档到 `docs/requirements/` 下
- 测试报告中的截图路径使用**相对路径**（`screenshots/xxx.png`）
- 功能名称与分支名或需求名保持一致，便于追溯
