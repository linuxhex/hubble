# Hubble 公众号文章 v2

## 推荐标题

**每次排查问题都要切 5 个系统？这个开源平台帮你一个界面搞定**

---

## 文章正文

```html
<section style="font-size: 16px; line-height: 1.8; color: #333; letter-spacing: 0.5px;">

<!-- 开头：场景共鸣 -->
<section style="background: linear-gradient(135deg, #667eea 0%, #764ba2 100%); padding: 28px; border-radius: 12px; margin: 20px 0; color: white;">
  <p style="margin: 0 0 12px; font-size: 17px;"><strong>凌晨 2 点，线上服务突然报错。</strong></p>
  <p style="margin: 0 0 12px;">你打开电脑，开始排查问题：</p>
  <p style="margin: 0;">
    先看网关日志 → 再查链路追踪 → 翻异常堆栈 → 盯监控大盘 → 分析性能指标...
  </p>
  <p style="margin: 12px 0 0; font-size: 17px;"><strong>一圈下来，半小时过去了。</strong></p>
</section>

<p style="margin: 20px 0; color: #555;">
  如果有这样一个平台：<strong>一个界面看所有监控，5 分钟定位问题根因</strong>，你会用吗？
</p>

<hr style="border: none; border-top: 1px solid #eee; margin: 28px 0;">

<!-- 产品介绍 -->
<h2 style="font-size: 20px; font-weight: bold; color: #333; margin: 28px 0 16px;">
  🚀 Hubble：全链路业务监控平台
</h2>

<p style="color: #555; margin: 12px 0;">
  Hubble 是一个开源的全链路监控平台，把网关流量、异常检测、性能分析、中间件监控、链路追踪等能力整合到一个界面。
</p>

<p style="color: #555; margin: 12px 0;">
  基于 <strong>Spring Boot 3 + Vue 3</strong>，集成阿里云 SLS、ARMS、CloudMonitor，支持 AI 智能分析。
</p>

<!-- 真实截图位置：系统首页 -->
<section style="background: #f8f9fa; padding: 20px; border-radius: 8px; margin: 20px 0; text-align: center;">
  <p style="margin: 0; color: #999; font-size: 14px;">📸 [系统首页截图]</p>
</section>

<hr style="border: none; border-top: 1px solid #eee; margin: 28px 0;">

<!-- 核心功能 -->
<h2 style="font-size: 20px; font-weight: bold; color: #333; margin: 28px 0 16px;">
  ⚡ 6 大核心能力
</h2>

<div style="background: #f8f9fa; padding: 16px 20px; border-radius: 8px; margin: 12px 0; border-left: 4px solid #07C160;">
  <p style="margin: 0 0 4px; font-weight: bold; color: #333;">🌐 网关概览</p>
  <p style="margin: 0; font-size: 15px; color: #666;">API 请求量、响应时间、错误率、QPS，一目了然</p>
</div>

<div style="background: #f8f9fa; padding: 16px 20px; border-radius: 8px; margin: 12px 0; border-left: 4px solid #fa5151;">
  <p style="margin: 0 0 4px; font-weight: bold; color: #333;">🐛 异常大盘</p>
  <p style="margin: 0; font-size: 15px; color: #666;">全链路异常聚合，快速定位错误根因</p>
</div>

<div style="background: #f8f9fa; padding: 16px 20px; border-radius: 8px; margin: 12px 0; border-left: 4px solid #ff8c00;">
  <p style="margin: 0 0 4px; font-weight: bold; color: #333;">📉 接口劣化检测</p>
  <p style="margin: 0; font-size: 15px; color: #666;">智能发现响应时间异常波动，提前预警</p>
</div>

<div style="background: #f8f9fa; padding: 16px 20px; border-radius: 8px; margin: 12px 0; border-left: 4px solid #1890ff;">
  <p style="margin: 0 0 4px; font-weight: bold; color: #333;">📈 流量暴涨预警</p>
  <p style="margin: 0; font-size: 15px; color: #666;">实时监测流量异常，防止系统过载</p>
</div>

<div style="background: #f8f9fa; padding: 16px 20px; border-radius: 8px; margin: 12px 0; border-left: 4px solid #722ed1;">
  <p style="margin: 0 0 4px; font-weight: bold; color: #333;">🔧 中间件监控</p>
  <p style="margin: 0; font-size: 15px; color: #666;">Redis、MySQL 性能监控，慢查询分析</p>
</div>

<div style="background: #f8f9fa; padding: 16px 20px; border-radius: 8px; margin: 12px 0; border-left: 4px solid #13c2c2;">
  <p style="margin: 0 0 4px; font-weight: bold; color: #333;">🔗 链路追踪</p>
  <p style="margin: 0; font-size: 15px; color: #666;">全链路调用追踪，可视化调用拓扑</p>
</div>

<!-- 真实截图位置：功能页面 -->
<section style="background: #f8f9fa; padding: 20px; border-radius: 8px; margin: 20px 0; text-align: center;">
  <p style="margin: 0; color: #999; font-size: 14px;">📸 [功能页面截图：网关概览/异常大盘/链路追踪]</p>
</section>

<hr style="border: none; border-top: 1px solid #eee; margin: 28px 0;">

<!-- 对比优势 -->
<h2 style="font-size: 20px; font-weight: bold; color: #333; margin: 28px 0 16px;">
  🎯 为什么选择 Hubble？
</h2>

<section style="background: #fff5f5; padding: 20px; border-radius: 8px; margin: 16px 0;">
  <p style="margin: 0 0 8px; font-weight: bold; color: #c53030;">❌ 传统方式</p>
  <ul style="margin: 0; padding-left: 20px; color: #742a2a;">
    <li>5 个工具来回切换</li>
    <li>排查问题 30 分钟+</li>
    <li>全靠人工经验</li>
    <li>被动响应告警</li>
  </ul>
</section>

<section style="background: #f0fff4; padding: 20px; border-radius: 8px; margin: 16px 0;">
  <p style="margin: 0 0 8px; font-weight: bold; color: #22543d;">✅ Hubble 方式</p>
  <ul style="margin: 0; padding-left: 20px; color: #22543d;">
    <li>一个平台看所有</li>
    <li>5 分钟定位根因</li>
    <li>AI 智能分析</li>
    <li>主动预警问题</li>
  </ul>
</section>

<hr style="border: none; border-top: 1px solid #eee; margin: 28px 0;">

<!-- 技术亮点 -->
<h2 style="font-size: 20px; font-weight: bold; color: #333; margin: 28px 0 16px;">
  💡 核心亮点
</h2>

<table style="width: 100%; border-collapse: collapse; margin: 16px 0; font-size: 15px;">
  <tr style="background: #07C160; color: white;">
    <th style="padding: 12px; text-align: left;">特性</th>
    <th style="padding: 12px; text-align: left;">说明</th>
  </tr>
  <tr style="background: #f9f9f9;">
    <td style="padding: 12px; border: 1px solid #e0e0e0;"><strong>AI 智能分析</strong></td>
    <td style="padding: 12px; border: 1px solid #e0e0e0;">基于 DashScope，智能识别异常模式</td>
  </tr>
  <tr>
    <td style="padding: 12px; border: 1px solid #e0e0e0;"><strong>全链路追踪</strong></td>
    <td style="padding: 12px; border: 1px solid #e0e0e0;">集成 ARMS，跨服务调用链追踪</td>
  </tr>
  <tr style="background: #f9f9f9;">
    <td style="padding: 12px; border: 1px solid #e0e0e0;"><strong>实时告警</strong></td>
    <td style="padding: 12px; border: 1px solid #e0e0e0;">钉钉、邮件多渠道通知</td>
  </tr>
  <tr>
    <td style="padding: 12px; border: 1px solid #e0e0e0;"><strong>开箱即用</strong></td>
    <td style="padding: 12px; border: 1px solid #e0e0e0;">单 JAR 部署，5 分钟搭建</td>
  </tr>
</table>

<hr style="border: none; border-top: 1px solid #eee; margin: 28px 0;">

<!-- 快速开始 -->
<h2 style="font-size: 20px; font-weight: bold; color: #333; margin: 28px 0 16px;">
  🚀 5 分钟快速开始
</h2>

<section style="background: #1a202c; color: #e2e8f0; padding: 20px; border-radius: 8px; margin: 16px 0; font-family: 'Courier New', monospace; font-size: 14px; line-height: 1.8;">
  <p style="margin: 0;">
    <span style="color: #68d391;"># 1. 克隆项目</span><br>
    git clone https://github.com/your-org/hubble.git<br><br>
    <span style="color: #68d391;"># 2. 配置环境</span><br>
    cp .env.example .env<br><br>
    <span style="color: #68d391;"># 3. 启动服务</span><br>
    java -jar hubble-server.jar<br><br>
    <span style="color: #68d391;"># 4. 访问系统</span><br>
    open http://localhost:82
  </p>
</section>

<section style="background: #ebf8ff; padding: 16px; border-radius: 8px; margin: 16px 0; border-left: 4px solid #4299e1;">
  <p style="margin: 0; color: #2c5282;">
    💡 <strong>提示：</strong>支持单文件部署，一个 JAR 包即可运行
  </p>
</section>

<hr style="border: none; border-top: 1px solid #eee; margin: 28px 0;">

<!-- 总结 -->
<h2 style="font-size: 20px; font-weight: bold; color: #333; margin: 28px 0 16px;">
  📝 总结
</h2>

<p style="color: #555; margin: 12px 0;">
  微服务架构下，监控复杂度常被低估。Hubble 通过<strong>一站式平台 + AI 分析 + 全链路追踪</strong>，让监控回归本质：
</p>

<section style="background: linear-gradient(135deg, #667eea 0%, #764ba2 100%); padding: 24px; border-radius: 12px; margin: 20px 0; color: white;">
  <p style="margin: 0; line-height: 2;">
    ✅ <strong>减少工具切换</strong> — 一个平台看所有<br>
    ✅ <strong>缩短排查时间</strong> — 从 30 分钟到 5 分钟<br>
    ✅ <strong>智能异常检测</strong> — 主动发现问题<br>
    ✅ <strong>开箱即用</strong> — 5 分钟完成部署
  </p>
</section>

<p style="color: #555; margin: 20px 0; font-size: 17px; text-align: center;">
  <strong>如果你也在为监控发愁，不妨试试 Hubble。</strong>
</p>

<hr style="border: none; border-top: 1px solid #eee; margin: 28px 0;">

<!-- 互动引导 -->
<section style="text-align: center; padding: 28px; background: #f7fafc; border-radius: 8px; margin: 20px 0;">
  <p style="margin: 0 0 8px; font-size: 18px; font-weight: bold; color: #2d3748;">
    👇 觉得有用？点个 <strong style="color: #07C160;">在看</strong> 支持一下
  </p>
  <p style="margin: 0; font-size: 15px; color: #718096;">
    有问题欢迎留言交流
  </p>
</section>

</section>
```

---

## 配图清单（需要真实系统截图）

| 序号 | 截图内容 | 页面路径 | 插入位置 |
|------|---------|---------|---------|
| 1 | 系统首页/监控大盘 | `/gateway` | 产品介绍后 |
| 2 | 网关概览页 | `/gateway` | 核心功能段 |
| 3 | 异常大盘页 | `/exception` | 核心功能段 |
| 4 | 链路追踪页 | `/trace` | 核心功能段 |
| 5 | 中间件监控页 | `/middleware` | 技术亮点段 |
| 6 | 服务负载页 | `/service-load` | 技术亮点段 |

**截图要求：**
- 分辨率：1920x1080 或更高
- 格式：PNG
- 确保数据已加载完成
- 包含完整页面内容

---

## 文章数据

- **字数**：约 1200 字（更简洁）
- **阅读时间**：约 4 分钟
- **配图**：6 张真实截图
- **适合人群**：技术负责人、运维工程师、后端开发

---

## 更新步骤

1. 手动截取 6 张系统页面截图
2. 登录公众号后台
3. 新建文章，输入标题
4. 复制 HTML 正文
5. 在标记位置插入对应截图
6. 保存草稿并发布
