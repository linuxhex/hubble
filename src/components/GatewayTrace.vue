<template>
  <div class="gateway-trace">
    <!-- 添加用户行为搜索框 -->
    <div class="behavior-search">
      <el-input
        v-model="behaviorSearchKeyword"
        placeholder="请输入关键字搜索用户行为"
        :prefix-icon="Search"
        clearable
        @input="handleBehaviorSearch"
      >
        <template #prepend>用户行为搜索</template>
      </el-input>
    </div>

    <div class="trace-list">
      <div class="trace-item" v-for="(item, index) in traceItems" :key="index">
        <div class="trace-expand" @click="toggleExpand(index)">
          <span class="expand-icon">{{ item.expanded ? '-' : '+' }}</span>
        </div>
        <div class="trace-content">
          <div class="trace-header">
            <div class="trace-info">
              <span class="trace-tag">{{ item.protocol }}</span>
              <span class="trace-type">{{ item.type }}</span>
              <span class="trace-status">{{ item.status }}</span>
              <span class="trace-ip">{{ item.ip }}</span>
              <span class="trace-time">{{ item.time }}</span>
            </div>
            <div class="trace-path">
              <el-button 
                type="primary" 
                link 
                @click.stop="showHttpDetail(item)"
              >{{ item.path }}</el-button>
            </div>
          </div>
          <div class="trace-details" v-if="item.expanded">
            <div v-for="(detail, dIndex) in item.details" :key="dIndex" class="detail-container">
              <div class="detail-row">
                <div class="trace-expand" v-if="detail.children" @click="toggleDetailExpand(index, dIndex)">
                  <span class="expand-icon">{{ detail.expanded ? '-' : '+' }}</span>
                </div>
                <div class="detail-content">
                  <div class="detail-info">
                    <span class="trace-tag">{{ detail.protocol }}</span>
                    <span class="detail-type">{{ detail.type }}</span>
                    <span class="detail-status">{{ detail.status }}</span>
                    <span class="detail-ip">{{ detail.ip }}</span>
                    <span class="detail-time">{{ detail.time }}</span>
                  </div>
                  <span class="detail-name">{{ detail.name }}</span>
                </div>
                <div class="trace-duration" v-if="detail.time">
                  <div class="duration-bar" :style="{ width: getDurationWidth(detail.time) + 'px' }" />
                </div>
              </div>
              <!-- 第三层 -->
              <div class="sub-details" v-if="detail.children && detail.expanded">
                <div v-for="(child, cIndex) in detail.children" :key="cIndex" class="detail-row">
                  <div class="trace-expand" v-if="child.children" @click="toggleChildExpand(index, dIndex, cIndex)">
                    <span class="expand-icon">{{ child.expanded ? '-' : '+' }}</span>
                  </div>
                  <div class="detail-content">
                    <div class="detail-info">
                      <span class="trace-tag">{{ child.protocol }}</span>
                      <span class="detail-type">{{ child.type }}</span>
                      <span class="detail-status">{{ child.status }}</span>
                      <span class="detail-ip">{{ child.ip }}</span>
                      <span class="detail-time">{{ child.time }}</span>
                    </div>
                    <span class="detail-name">{{ child.name }}</span>
                  </div>
                  <div class="trace-duration" v-if="child.time">
                    <div class="duration-bar" :style="{ width: getDurationWidth(child.time) + 'px' }" />
                  </div>
                </div>
              </div>
            </div>
          </div>
        </div>
        <div class="trace-duration">
          <div class="duration-bar" :style="{ width: getDurationWidth(item.time) + 'px' }" />
        </div>
      </div>
    </div>

        <!-- HTTP详情弹窗 -->
        <el-dialog
      v-model="httpDetailVisible"
      title="接口详情"
      width="800px"
      destroy-on-close
      class="http-detail-dialog"
    >
      <div class="http-detail">
        <div class="section">
          <div class="section-header request">
            <div class="header-content">
              <el-icon><Upload /></el-icon>
              <span class="title">请求参数</span>
            </div>
          </div>
          <div class="section-content">
            <pre class="json-content">{{ httpDetail.requestBody }}</pre>
          </div>
        </div>

        <div class="section">
          <div class="section-header response">
            <div class="header-content">
              <el-icon><Download /></el-icon>
              <span class="title">返回结果</span>
            </div>
          </div>
          <div class="section-content">
            <pre class="json-content">{{ httpDetail.responseBody }}</pre>
          </div>
        </div>

        <div class="section">
          <div class="section-header request">
            <div class="title-with-action">
              <div class="header-content">
                <el-icon><Document /></el-icon>
                <span class="title">请求头</span>
              </div>
              <el-button 
                type="primary" 
                link 
                @click="httpDetail.showRequestHeaders = !httpDetail.showRequestHeaders"
              >
                {{ httpDetail.showRequestHeaders ? '收起' : '展开' }}
                <el-icon class="header-icon" :class="{ 'is-active': httpDetail.showRequestHeaders }">
                  <ArrowDown />
                </el-icon>
              </el-button>
            </div>
          </div>
          <div class="section-content" v-show="httpDetail.showRequestHeaders">
            <pre class="json-content">{{ httpDetail.requestHeaders }}</pre>
          </div>
        </div>

        <div class="section">
          <div class="section-header response">
            <div class="title-with-action">
              <div class="header-content">
                <el-icon><Document /></el-icon>
                <span class="title">响应头</span>
              </div>
              <el-button 
                type="primary" 
                link 
                @click="httpDetail.showResponseHeaders = !httpDetail.showResponseHeaders"
              >
                {{ httpDetail.showResponseHeaders ? '收起' : '展开' }}
                <el-icon class="header-icon" :class="{ 'is-active': httpDetail.showResponseHeaders }">
                  <ArrowDown />
                </el-icon>
              </el-button>
            </div>
          </div>
          <div class="section-content" v-show="httpDetail.showResponseHeaders">
            <pre class="json-content">{{ httpDetail.responseHeaders }}</pre>
          </div>
        </div>
      </div>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive } from 'vue'
import { Upload, Download, Document, ArrowDown, Search } from '@element-plus/icons-vue'

// HTTP详情弹窗相关
const httpDetailVisible = ref(false)
const httpDetail = reactive({
  requestBody: '',
  responseBody: '',
  requestHeaders: '',
  responseHeaders: '',
  showRequestHeaders: false,
  showResponseHeaders: false
})

// 用户行为搜索相关
const behaviorSearchKeyword = ref('')
const handleBehaviorSearch = () => {
  // 实现搜索逻辑
  console.log('搜索关键字:', behaviorSearchKeyword.value)
}

// 处理HTTP详情点击
const showHttpDetail = (item) => {
  httpDetailVisible.value = true
  
  // 设置请求参数
  httpDetail.requestBody = JSON.stringify({
    path: item.path,
    method: 'POST',
    timestamp: new Date().toISOString(),
    duration: item.time,
    app: item.protocol,
    ip: item.ip
  }, null, 2)

  // 设置返回结果
  httpDetail.responseBody = JSON.stringify({
    code: 200,
    message: "success",
    data: {
      traceId: item.id || '',
      status: "COMPLETED",
      duration: item.time,
      timestamp: new Date().toISOString()
    }
  }, null, 2)

  // 设置请求头
  httpDetail.requestHeaders = JSON.stringify({
    "Content-Type": "application/json",
    "X-Request-ID": item.id || '',
    "X-Trace-ID": item.id || '',
    "User-Agent": "Mozilla/5.0",
    "Accept": "application/json",
    "Accept-Language": "zh-CN,zh;q=0.9",
    "Accept-Encoding": "gzip, deflate, br",
    "Connection": "keep-alive"
  }, null, 2)

  // 设置响应头
  httpDetail.responseHeaders = JSON.stringify({
    "Content-Type": "application/json;charset=UTF-8",
    "Transfer-Encoding": "chunked",
    "Connection": "keep-alive",
    "X-Application-Context": "application:production",
    "X-Content-Type-Options": "nosniff",
    "X-XSS-Protection": "1; mode=block",
    "Cache-Control": "no-cache, no-store, max-age=0, must-revalidate",
    "Pragma": "no-cache",
    "Expires": "0",
    "X-Frame-Options": "DENY",
    "Content-Language": "zh-CN",
    "X-Response-Time": `${item.time}`
  }, null, 2)
}

// 处理URL点击
const handleUrlClick = (row) => {
  httpDetailVisible.value = true
  // 模拟HTTP请求详情数据
  httpDetail.requestBody = JSON.stringify({
    userId: row.userId,
    pageSize: 10,
    pageNum: 1,
    queryTime: row.timestamp
  }, null, 2)

  httpDetail.responseBody = JSON.stringify({
    code: row.statusCode,
    message: "success",
    data: {
      total: 42,
      list: [
        {
          id: "123456",
          status: "PROCESSING",
          createTime: row.timestamp
        }
      ]
    }
  }, null, 2)
}

// 模拟数据，增加更多层级
const traceItems = ref([
  {
    path: '/ld-uc-app/driver/certificate/aggregation/init',
    protocol: 'hango',
    type: 'URL',
    status: '南京绿火',
    ip: '10.13.68.96',
    time: '582.15ms',
    expanded: true,
    details: [
      {
        name: 'ld-uc-app',
        protocol: 'hango',
        type: 'GlobalGreyRule',
        status: '南京绿火',
        ip: '10.13.68.96',
        time: '0.02ms',
        expanded: true,
        children: [
          {
            name: 'com.ymm.uc.app.service.impl.DriverCertificateServiceImpl.aggregationInit',
            protocol: 'hango',
            type: 'Method',
            status: '南京绿火',
            ip: '10.13.68.96',
            time: '0.01ms',
            expanded: false,
            children: [
              {
                name: '[日志] 开始聚合驾驶证信息...',
                protocol: 'log',
                type: 'INFO',
                status: '南京绿火',
                ip: '10.13.68.96',
                time: '0.001ms'
              }
            ]
          }
        ]
      },
      {
        name: 'YMM',
        protocol: 'hango',
        type: 'Decryption',
        status: '南京绿火',
        ip: '10.13.68.96',
        time: '0.01ms',
        expanded: false,
        children: [
          {
            name: 'com.ymm.security.DecryptionService.process',
            protocol: 'hango',
            type: 'Method',
            status: '南京绿火',
            ip: '10.13.68.96',
            time: '0.005ms',
            expanded: false,
            children: [
              {
                name: '[日志] 解密处理开始',
                protocol: 'log',
                type: 'INFO',
                status: '南京绿火',
                ip: '10.13.68.96',
                time: '0.001ms'
              }
            ]
          }
        ]
      }
    ]
  },
  {
    path: '/ld-uc-app/driver/certificate/aggregation/init',
    protocol: 'ld-uc-app',
    type: 'NettyHttp',
    status: '南京绿火机房',
    ip: '172.29.164.216',
    time: '574.67ms',
    expanded: false,
    details: [
      {
        name: 'NettyHttp',
        protocol: 'ld-uc-app',
        type: 'HTTP',
        status: '南京绿火机房',
        ip: '172.29.164.216',
        time: '2.15ms',
        expanded: false,
        children: [
          {
            name: 'HttpProcess',
            protocol: 'ld-uc-app',
            type: 'Internal',
            status: '南京绿火机房',
            ip: '172.29.164.216',
            time: '1.05ms'
          }
        ]
      }
    ]
  },
  {
    path: 'com.ymm.uc.info.api.service.account.IAccountQueryService:getBaseInfoByAccountId',
    protocol: 'uc-info-server',
    type: 'PigeonCall',
    status: '南京绿火机房',
    ip: '172.29.70.149',
    time: '4ms',
    expanded: false,
    details: [
      {
        name: 'AccountQueryServiceImpl.getBaseInfoByAccountId',
        protocol: 'uc-info-server',
        type: 'Method',
        status: '南京绿火机房',
        ip: '172.29.70.149',
        time: '3.5ms',
        expanded: false,
        children: [
          {
            name: 'com.ymm.uc.info.dao.AccountMapper.selectById',
            protocol: 'uc-info-server',
            type: 'SQL',
            status: '南京绿火机房',
            ip: '172.29.70.149',
            time: '2.8ms',
            expanded: false,
            children: [
              {
                name: '[SQL] SELECT * FROM t_account WHERE id = 123',
                protocol: 'log',
                type: 'SQL',
                status: '南京绿火机房',
                ip: '172.29.70.149',
                time: '2.5ms'
              }
            ]
          }
        ]
      }
    ]
  },
  {
    path: 'com.ymm.uc.info.api.service.certificate.IUserViolationService:queryByCondition',
    protocol: 'uc-info-server',
    type: 'PigeonCall',
    status: '南京绿火机房',
    ip: '172.29.30.121',
    time: '7ms',
    expanded: false,
    details: [
      {
        name: 'UserViolationServiceImpl.queryByCondition',
        protocol: 'uc-info-server',
        type: 'Method',
        status: '南京绿火机房',
        ip: '172.29.30.121',
        time: '6.5ms',
        expanded: false,
        children: [
          {
            name: 'com.ymm.uc.info.dao.ViolationMapper.selectByCondition',
            protocol: 'uc-info-server',
            type: 'SQL',
            status: '南京绿火机房',
            ip: '172.29.30.121',
            time: '5.8ms',
            expanded: false,
            children: [
              {
                name: '[SQL] SELECT * FROM t_violation WHERE user_id = ? AND status = ?',
                protocol: 'log',
                type: 'SQL',
                status: '南京绿火机房',
                ip: '172.29.30.121',
                time: '5.5ms'
              },
              {
                name: '[日志] 查询到违规记录数: 3',
                protocol: 'log',
                type: 'INFO',
                status: '南京绿火机房',
                ip: '172.29.30.121',
                time: '0.1ms'
              }
            ]
          }
        ]
      }
    ]
  },
  {
    path: 'sdc:truckCheckRuleFacade_1.0.0:isHitCheckRule',
    protocol: 'trade-sdc-app',
    type: 'PigeonCall',
    status: '南京绿火机房',
    ip: '172.29.127.22',
    time: '13ms',
    expanded: false,
    details: [
      {
        name: 'TruckCheckRuleFacadeImpl.isHitCheckRule',
        protocol: 'trade-sdc-app',
        type: 'Method',
        status: '南京绿火机房',
        ip: '172.29.127.22',
        time: '12.5ms',
        expanded: false,
        children: [
          {
            name: 'com.ymm.sdc.rule.RuleEngine.evaluate',
            protocol: 'trade-sdc-app',
            type: 'Method',
            status: '南京绿火机房',
            ip: '172.29.127.22',
            time: '10.8ms',
            expanded: false,
            children: [
              {
                name: '[日志] 开始规则引擎评估',
                protocol: 'log',
                type: 'INFO',
                status: '南京绿火机房',
                ip: '172.29.127.22',
                time: '0.1ms'
              },
              {
                name: '[日志] 加载规则配置: truck_check_rules',
                protocol: 'log',
                type: 'INFO',
                status: '南京绿火机房',
                ip: '172.29.127.22',
                time: '0.2ms'
              },
              {
                name: '[日志] 规则评估结果: 通过',
                protocol: 'log',
                type: 'INFO',
                status: '南京绿火机房',
                ip: '172.29.127.22',
                time: '0.1ms'
              }
            ]
          }
        ]
      }
    ]
  }
])

const toggleExpand = (index) => {
  traceItems.value[index].expanded = !traceItems.value[index].expanded
}

const toggleDetailExpand = (itemIndex, detailIndex) => {
  traceItems.value[itemIndex].details[detailIndex].expanded = !traceItems.value[itemIndex].details[detailIndex].expanded
}

const toggleChildExpand = (itemIndex, detailIndex, childIndex) => {
  traceItems.value[itemIndex].details[detailIndex].children[childIndex].expanded = 
    !traceItems.value[itemIndex].details[detailIndex].children[childIndex].expanded
}

const getDurationWidth = (time) => {
  const ms = parseFloat(time)
  return Math.min(Math.max(ms * 2, 20), 200)
}
</script>

<style scoped>
.gateway-trace {
  padding: 16px;
  height: 100%;
  display: flex;
  flex-direction: column;
  gap: 24px;
  background: #f5f7fa;
}

.behavior-search {
  background: white;
  padding: 12px 24px;
  border-radius: 4px;
}

.behavior-search :deep(.el-input-group__prepend) {
  background-color: #f5f7fa;
  border-color: #dcdfe6;
  color: #606266;
  padding: 0 12px;
  font-size: 13px;
  font-weight: 500;
  width: 100px;
  text-align: center;
}

.behavior-search :deep(.el-input__wrapper) {
  padding-left: 8px;
}

.behavior-search :deep(.el-input__inner) {
  height: 32px;
  font-size: 13px;
}

.behavior-search :deep(.el-input__clear) {
  margin-right: 8px;
}

.trace-list {
  flex: 1;
  overflow: auto;
}

.trace-item {
  display: flex;
  align-items: flex-start;
  padding: 4px 12px;
  border-bottom: 1px solid #f0f0f0;
}

.trace-expand {
  width: 16px;
  height: 16px;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  margin-top: 2px;
}

.expand-icon {
  font-size: 14px;
  color: #666;
  font-family: monospace;
  line-height: 1;
}

.trace-content {
  flex: 1;
  margin: 0 8px;
  min-width: 0;
}

.trace-header {
  display: flex;
  flex-direction: column;
  font-size: 12px;
  gap: 2px;
}

.trace-info, .detail-info {
  display: flex;
  align-items: center;
  gap: 12px;
  color: #666;
}

.trace-tag, .detail-tag {
  background: #f5f5f5;
  padding: 1px 4px;
  border-radius: 2px;
  font-size: 11px;
  color: #666;
}

.trace-path {
  margin-top: 4px;
}

.trace-path :deep(.el-button--primary.is-link) {
  padding: 0;
  font-size: 13px;
  font-weight: normal;
  height: auto;
  line-height: 1.5;
}

.trace-path :deep(.el-button--primary.is-link:hover) {
  opacity: 0.8;
}

.trace-details {
  margin-top: 4px;
  padding-left: 24px;
  border-left: 1px dashed #e8e8e8;
  margin-left: 4px;
}

.detail-container {
  margin-bottom: 4px;
}

.detail-row {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  padding: 2px 0;
}

.detail-content {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.detail-name {
  color: #1890ff;
  cursor: pointer;
  font-size: 12px;
}

.sub-details {
  margin-left: 24px;
  padding-left: 24px;
  border-left: 1px dashed #e8e8e8;
  margin-top: 4px;
}

.trace-duration {
  width: 200px;
  display: flex;
  align-items: center;
  padding-top: 4px;
}

.duration-bar {
  height: 12px;
  background: #95de64;
  border-radius: 2px;
}

.trace-type, .trace-status, .trace-ip, .trace-time,
.detail-type, .detail-status, .detail-ip, .detail-time {
  font-size: 11px;
  color: #666;
}

.trace-ip {
  color: #1890ff;
}

/* HTTP详情弹窗样式 */
:deep(.http-detail-dialog .el-dialog__header) {
  margin: 0;
  padding: 16px 20px;
  border-bottom: 1px solid #ebeef5;
}

:deep(.http-detail-dialog .el-dialog__headerbtn) {
  top: 16px;
}

:deep(.http-detail-dialog .el-dialog__title) {
  font-size: 16px;
  font-weight: 600;
  color: #1f2937;
}

:deep(.http-detail-dialog .el-dialog__body) {
  padding: 20px;
}

.http-detail {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.section {
  border: 1px solid #ebeef5;
  border-radius: 6px;
  overflow: hidden;
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.05);
}

.section-header {
  padding: 12px 16px;
  border-bottom: 1px solid #ebeef5;
  transition: background-color 0.3s;
}

.section-header.request {
  background: linear-gradient(to right, #e6f7ff, #f0f9ff);
}

.section-header.response {
  background: linear-gradient(to right, #f6ffed, #f9fff5);
}

.header-content {
  display: flex;
  align-items: center;
  gap: 8px;
}

.header-content .el-icon {
  font-size: 16px;
}

.section-header.request .el-icon {
  color: #1890ff;
}

.section-header.response .el-icon {
  color: #52c41a;
}

.title {
  font-size: 14px;
  font-weight: 600;
}

.section-header.request .title {
  color: #1890ff;
}

.section-header.response .title {
  color: #52c41a;
}

.title-with-action {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.section-content {
  padding: 16px;
  background-color: #fafafa;
  transition: background-color 0.3s;
}

.section-content:hover {
  background-color: #f5f5f5;
}

.json-content {
  margin: 0;
  white-space: pre-wrap;
  word-break: break-all;
  font-family: "Monaco", "Menlo", "Ubuntu Mono", "Consolas", "source-code-pro", monospace;
  font-size: 13px;
  line-height: 1.6;
  color: #24292e;
}

.json-content ::selection {
  background: #b3d4fc;
}

.header-icon {
  margin-left: 4px;
  transition: transform 0.3s;
}

.header-icon.is-active {
  transform: rotate(180deg);
}

:deep(.el-button--primary.is-link) {
  color: #1890ff;
  font-size: 13px;
  height: 32px;
  padding: 0 8px;
}

:deep(.el-button--primary.is-link:hover) {
  color: #40a9ff;
}
</style> 