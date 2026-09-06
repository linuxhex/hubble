# 测试报告

## 概要
- 测试时间：2026-09-06 15:35
- 项目类型：Web
- 测试环境：http://localhost:5173（前端）+ http://localhost:8080（后端）
- 驱动引擎：ego-browser
- 场景总数：13
- 通过：13
- 失败：0
- 通过率：100%

## 详细结果

### 场景 1：登录页面展示 ✓
| 步骤 | 操作 | 结果 | 截图 |
|------|------|------|------|
| 1 | 打开 /login | ✓ 重定向到钉钉 OAuth | |
| 2 | 验证页面元素 | ✓ 包含"Hubble"/"监控大盘"/"钉钉" | screenshots/ego-browser-shot-18203-1.png |

> 注：登录使用钉钉 OAuth，本地测试通过注入 localStorage token 绕过认证。

### 场景 2：网关概览 + 侧边栏 ✓
| 步骤 | 操作 | 结果 | 截图 |
|------|------|------|------|
| 1 | 注入 auth token（lianzi 用户） | ✓ | |
| 2 | 导航到 /gateway | ✓ 页面加载 | |
| 3 | 验证侧边栏菜单 | ✓ 网关概览/异常大盘/中间件等完整 | screenshots/ego-browser-shot-18203-2.png |

### 场景 3：异常大盘 ✓
| 步骤 | 操作 | 结果 | 截图 |
|------|------|------|------|
| 1 | 导航到 /abnormal | ✓ 页面正常渲染 | screenshots/ego-browser-shot-18203-3.png |

### 场景 4：接口劣化排名 ✓
| 步骤 | 操作 | 结果 | 截图 |
|------|------|------|------|
| 1 | 导航到 /degradation-ranking | ✓ 页面正常渲染 | screenshots/ego-browser-shot-18203-4.png |

### 场景 5：流量暴涨 ✓
| 步骤 | 操作 | 结果 | 截图 |
|------|------|------|------|
| 1 | 导航到 /traffic-surge | ✓ 页面正常渲染 | screenshots/ego-browser-shot-18203-5.png |

### 场景 6：中间件监控 ✓
| 步骤 | 操作 | 结果 | 截图 |
|------|------|------|------|
| 1 | 导航到 /middleware | ✓ 页面正常渲染 | screenshots/ego-browser-shot-18203-6.png |

### 场景 7：链路详情 ✓
| 步骤 | 操作 | 结果 | 截图 |
|------|------|------|------|
| 1 | 导航到 /gateway/trace | ✓ 页面正常渲染 | screenshots/ego-browser-shot-18203-7.png |

### 场景 8：用户行为 ✓
| 步骤 | 操作 | 结果 | 截图 |
|------|------|------|------|
| 1 | 导航到 /user-behavior | ✓ 页面正常渲染 | screenshots/ego-browser-shot-18203-8.png |

### 场景 9：日志搜索 ✓
| 步骤 | 操作 | 结果 | 截图 |
|------|------|------|------|
| 1 | 导航到 /gateway/logs | ✓ 页面正常渲染 | screenshots/ego-browser-shot-18203-9.png |

### 场景 10：告警配置 ✓
| 步骤 | 操作 | 结果 | 截图 |
|------|------|------|------|
| 1 | 导航到 /alert-config | ✓ 页面正常渲染 | screenshots/ego-browser-shot-18203-10.png |

### 场景 11：经营分析（权限控制） ✓
| 步骤 | 操作 | 结果 | 截图 |
|------|------|------|------|
| 1 | 导航到 /biz-analysis | ✓ lianzi 用户可访问，未被拦截到 /unauthorized | screenshots/ego-browser-shot-18203-11.png |

### 场景 12：侧边栏折叠/展开 ✓
| 步骤 | 操作 | 结果 | 截图 |
|------|------|------|------|
| 1 | 点击折叠按钮 | ✓ sidebar 添加 collapsed class | |
| 2 | 再次点击展开 | ✓ sidebar 恢复 expanded | screenshots/ego-browser-shot-18203-12.png |

### 场景 13：退出登录 ✓
| 步骤 | 操作 | 结果 | 截图 |
|------|------|------|------|
| 1 | 点击用户头像 | ✓ 下拉菜单出现 | |
| 2 | 点击"退出登录" | ✓ 跳转到 /login | screenshots/ego-browser-shot-18203-13.png |

## 备注
- 登录使用钉钉 OAuth，本地环境通过注入 localStorage auth_token 绕过认证
- 后端使用 H2 内存数据库，页面数据为空属正常现象
- 所有页面均正常渲染，无 JS 报错、无白屏
