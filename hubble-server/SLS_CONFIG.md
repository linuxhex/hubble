# SLS 配置说明

## 快速开始

1. 复制配置模板：
```bash
cd backend
cp .env.example .env
```

2. 编辑 `.env` 文件，填写真实配置：
```bash
ALIYUN_SLS_ACCESS_KEY_ID=你的AccessKeyID
ALIYUN_SLS_ACCESS_KEY_SECRET=你的AccessKeySecret
ALIYUN_SLS_PROJECT=你的SLS项目名
ALIYUN_SLS_ENDPOINT=cn-hangzhou.log.aliyuncs.com
```

3. 启动后端：
```bash
./start.sh
```

## 获取 SLS 配置

### 1. AccessKey
- 登录阿里云控制台
- 右上角头像 → AccessKey 管理
- 创建或使用已有的 AccessKey

### 2. SLS Project
- 登录 [日志服务控制台](https://sls.console.aliyun.com/)
- 选择或创建 Project
- Project 名称填入 `ALIYUN_SLS_PROJECT`

### 3. Endpoint
- 在 SLS Project 概览页查看 Endpoint
- 格式：`{region}.log.aliyuncs.com`
- 例如：`cn-hangzhou.log.aliyuncs.com`

## 验证配置

启动后访问：
- http://localhost:5173/gateway - 网关大盘
- http://localhost:5173/gateway/logs - 日志搜索

如果看到真实数据，说明配置成功。

## 注意事项

- `.env` 文件已加入 `.gitignore`，不会提交到 Git
- 不要将 `.env` 文件分享给他人
- 生产环境建议使用环境变量或密钥管理服务
