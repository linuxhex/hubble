<template>
  <div class="gateway-logs">
    <!-- 搜索条件区域 -->
    <div class="search-area">
      <el-form :model="searchForm" inline class="search-form">
        <el-form-item label="应用名称" class="no-margin">
          <el-input v-model="searchForm.appName" placeholder="cargo-ltl-app" size="small" style="width: 160px">
            <template #append>
              <el-button>更多</el-button>
            </template>
          </el-input>
        </el-form-item>
        <el-form-item label="请求URL" class="no-margin">
          <el-input v-model="searchForm.url" placeholder="URL: example.ymm-xxx-app/xxx" size="small" style="width: 300px" />
        </el-form-item>
        <el-form-item label="状态码" class="no-margin">
          <el-input v-model="searchForm.statusCode" placeholder="请输入状态码" size="small" style="width: 100px" />
        </el-form-item>
        <el-form-item label="手机号" class="no-margin">
          <el-input v-model="searchForm.phone" placeholder="请输入手机号" size="small" style="width: 120px" />
        </el-form-item>
        <el-form-item label="用户ID" class="no-margin">
          <el-input v-model="searchForm.userId" placeholder="请输入用户ID" size="small" style="width: 120px" />
        </el-form-item>
        <el-form-item label="链路ID" class="no-margin">
          <el-input v-model="searchForm.traceId" placeholder="请输入链路ID" size="small" style="width: 120px" />
        </el-form-item>
        <el-form-item class="no-margin operation-buttons">
          <el-button type="primary" size="small">查询</el-button>
          <el-button type="primary" size="small">查全网</el-button>
          <el-button size="small" circle>
            <el-icon><Refresh /></el-icon>
          </el-button>
          <el-tooltip content="日志分布" placement="top">
            <el-button size="small" circle @click="showLogDistribution">
              <el-icon><Histogram /></el-icon>
            </el-button>
          </el-tooltip>
          <el-tooltip content="状态码分布" placement="top">
            <el-button size="small" circle @click="showStatusDistribution">
              <el-icon><PieChart /></el-icon>
            </el-button>
          </el-tooltip>
        </el-form-item>
      </el-form>
    </div>

    <!-- 日志分布弹窗 -->
    <el-dialog
      v-model="logDistributionVisible"
      title="日志分布"
      width="600px"
      destroy-on-close
    >
      <div ref="logChartRef" style="height: 400px"></div>
    </el-dialog>

    <!-- 状态码分布弹窗 -->
    <el-dialog
      v-model="statusDistributionVisible"
      title="状态码分布"
      width="600px"
      destroy-on-close
    >
      <div ref="statusChartRef" style="height: 400px"></div>
    </el-dialog>

    <!-- 日志列表 -->
    <div class="log-list">
      <div class="list-header">
        <span>共搜索41条数据</span>
      </div>
      <el-table :data="logs" style="width: 100%" size="small" border>
        <el-table-column prop="appName" label="项目名" width="120" />
        <el-table-column prop="serverIp" label="服务器IP" width="120" />
        <el-table-column prop="url" label="URL" min-width="300" show-overflow-tooltip>
          <template #default="scope">
            <el-button 
              type="primary" 
              link 
              @click="handleUrlClick(scope.row)"
            >{{ scope.row.url }}</el-button>
          </template>
        </el-table-column>
        <el-table-column prop="userId" label="用户ID" width="180" show-overflow-tooltip />
        <el-table-column prop="duration" label="耗时(ms)" width="100" sortable>
          <template #default="scope">
            <span>{{ scope.row.duration.toFixed(2) }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="timestamp" label="发生时间" width="180" />
        <el-table-column prop="statusCode" label="状态码" width="80">
          <template #default="scope">
            <span :class="{ 'success-status': scope.row.statusCode === 200 }">{{ scope.row.statusCode }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="message" label="调用源" width="150" />
        <el-table-column fixed="right" label="操作" width="120">
          <template #default="scope">
            <div class="operation-cell">
              <el-button 
                type="text" 
                size="small" 
                @click="handleTraceClick(scope.row)"
              >链路</el-button>
              <el-divider direction="vertical" />
              <el-button 
                type="text" 
                size="small"
                @click="handleDetailClick(scope.row)"
              >详情</el-button>
            </div>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <!-- 详情弹窗 -->
    <el-dialog
      v-model="detailVisible"
      title="链路详情"
      width="900px"
      destroy-on-close
    >
      <div class="detail-content">
        <div v-for="item in detailData" :key="item.id" class="trace-item">
          <div class="trace-header" @click="toggleExpand(item)">
            <span class="expand-icon">{{ item.expanded ? '-' : '+' }}</span>
            <span class="trace-type" :class="item.type">{{ item.type }}</span>
            <span class="trace-name">
              <template v-if="item.type === 'HTTP'">
                <el-button 
                  type="primary" 
                  link 
                  @click.stop="showHttpDetail(item)"
                >{{ item.name }}</el-button>
              </template>
              <template v-else>{{ item.name }}</template>
            </span>
            <span class="trace-duration">{{ item.duration }}ms</span>
          </div>
          <div v-if="item.detail" class="trace-detail" v-show="item.expanded">
            <pre>{{ item.detail }}</pre>
          </div>
          <div v-if="item.children && item.expanded" class="trace-children" style="margin-left: 20px">
            <div v-for="child in item.children" :key="child.id" class="trace-item">
              <div class="trace-header" @click="toggleExpand(child)">
                <span class="expand-icon">{{ child.expanded ? '-' : '+' }}</span>
                <span class="trace-type" :class="child.type">{{ child.type }}</span>
                <span class="trace-name">{{ child.name }}</span>
                <span class="trace-duration">{{ child.duration }}ms</span>
              </div>
              <div v-if="child.detail" class="trace-detail" v-show="child.expanded">
                <pre>{{ child.detail }}</pre>
              </div>
              <div v-if="child.children && child.expanded" class="trace-children" style="margin-left: 20px">
                <div v-for="subChild in child.children" :key="subChild.id" class="trace-item">
                  <div class="trace-header" @click="toggleExpand(subChild)">
                    <span class="expand-icon">{{ subChild.expanded ? '-' : '+' }}</span>
                    <span class="trace-type" :class="subChild.type">{{ subChild.type }}</span>
                    <span class="trace-name">{{ subChild.name }}</span>
                    <span class="trace-duration">{{ subChild.duration }}ms</span>
                  </div>
                  <div v-if="subChild.detail" class="trace-detail" v-show="subChild.expanded">
                    <pre>{{ subChild.detail }}</pre>
                  </div>
                  <div v-if="subChild.children && subChild.expanded" class="trace-children" style="margin-left: 20px">
                    <div v-for="grandChild in subChild.children" :key="grandChild.id" class="trace-item">
                      <div class="trace-header" @click="toggleExpand(grandChild)">
                        <span class="expand-icon">{{ grandChild.expanded ? '-' : '+' }}</span>
                        <span class="trace-type" :class="grandChild.type">{{ grandChild.type }}</span>
                        <span class="trace-name">{{ grandChild.name }}</span>
                        <span class="trace-duration">{{ grandChild.duration }}ms</span>
                      </div>
                      <div v-if="grandChild.detail" class="trace-detail" v-show="grandChild.expanded">
                        <pre>{{ grandChild.detail }}</pre>
                      </div>
                    </div>
                  </div>
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>
    </el-dialog>

    <!-- HTTP详情弹窗 -->
    <el-dialog
      v-model="httpDetailVisible"
      title="接口详情"
      width="800px"
      destroy-on-close
      class="http-detail-dialog"
    >
      <div class="http-detail">
        <!-- 基本信息部分，仅在详情按钮点击时显示 -->
        <template v-if="showFullDetail">
          <div class="section">
            <div class="section-header">
              <div class="header-content">
                <el-icon><Document /></el-icon>
                <span class="title">基本信息</span>
              </div>
            </div>
            <div class="section-content">
              <div class="info-item">
                <span class="label">请求方式：</span>
                <span class="value method">{{ httpDetail.method }}</span>
              </div>
              <div class="info-item">
                <span class="label">请求路径：</span>
                <span class="value">{{ httpDetail.path }}</span>
              </div>
              <div class="info-item">
                <span class="label">请求来源：</span>
                <span class="value source">{{ httpDetail.source }}</span>
              </div>
              <div class="info-item">
                <span class="label">请求时间：</span>
                <span class="value">{{ httpDetail.timestamp }}</span>
              </div>
              <div class="info-item">
                <span class="label">响应状态：</span>
                <span class="value status" :class="httpDetail.status === 200 ? 'success' : 'error'">
                  {{ httpDetail.status }}
                </span>
              </div>
              <div class="info-item">
                <span class="label">接口耗时：</span>
                <span class="value duration">{{ httpDetail.duration }}ms</span>
              </div>
            </div>
          </div>
        </template>

        <!-- 请求参数部分 -->
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

        <!-- 返回结果部分 -->
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

        <!-- 请求头部分，仅在详情按钮点击时显示 -->
        <template v-if="showFullDetail">
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

          <!-- 响应头部分，仅在详情按钮点击时显示 -->
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
        </template>
      </div>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted, watch } from 'vue'
import { Refresh, Histogram, PieChart, Upload, Download, Document, ArrowDown } from '@element-plus/icons-vue'
import { useRouter } from 'vue-router'
import * as echarts from 'echarts'

const router = useRouter()

// 搜索表单
const searchForm = reactive({
  appName: '',
  url: '',
  statusCode: '',
  phone: '',
  userId: '',
  traceId: ''
})

// 处理链路点击
const handleTraceClick = (row) => {
  router.push({
    path: '/gateway/trace',
    query: {
      traceId: row.traceId,
      appName: row.appName,
      timestamp: row.timestamp
    }
  })
}

// 日志数据
const logs = ref([
  {
    appName: 'cargo-ltl-app',
    serverIp: '172.29.32.183',
    url: 'POST /cargo-ltl-app/ltlline/detainment/endPage',
    userId: '96500606549780783B',
    duration: 20.64,
    timestamp: '2022-01-21 15:52:06.590',
    statusCode: 200,
    message: '安卓｜小程序',
    traceId: '96500606549780783B_20220121155206'
  },
  {
    appName: 'cargo-ltl-app',
    serverIp: '172.29.32.183',
    url: 'POST /cargo-ltl-app/ltlline/detainment/endPage',
    userId: '96500606549780783B',
    duration: 17.37,
    timestamp: '2022-01-21 15:38:02.320',
    statusCode: 200,
    message: 'IOS',
    traceId: '96500606549780783B_20220121153802'
  },
  {
    appName: 'cargo-ltl-app',
    serverIp: '172.29.32.183',
    url: 'POST /cargo-ltl-app/v3/cargo/publish/complete',
    userId: '96500606549737168B',
    duration: 2285.02,
    timestamp: '2022-01-21 15:37:49.843',
    statusCode: 200,
    message: '小程序'
  }
  // ... 更多数据
])

// 分布图相关
const logDistributionVisible = ref(false)
const statusDistributionVisible = ref(false)
const logChartRef = ref(null)
const statusChartRef = ref(null)
let logChart = null
let statusChart = null

// 显示日志分布
const showLogDistribution = () => {
  logDistributionVisible.value = true
  setTimeout(() => {
    if (!logChart) {
      logChart = echarts.init(logChartRef.value)
    }
    
    const option = {
      tooltip: {
        trigger: 'axis',
        axisPointer: {
          type: 'shadow'
        }
      },
      grid: {
        left: '3%',
        right: '4%',
        bottom: '3%',
        containLabel: true
      },
      xAxis: {
        type: 'category',
        data: ['00:00', '02:00', '04:00', '06:00', '08:00', '10:00', '12:00', 
               '14:00', '16:00', '18:00', '20:00', '22:00']
      },
      yAxis: {
        type: 'value'
      },
      series: [
        {
          name: '请求数',
          type: 'bar',
          data: [120, 80, 60, 40, 180, 220, 280, 300, 260, 220, 180, 140],
          itemStyle: {
            color: '#409EFF'
          }
        }
      ]
    }
    
    logChart.setOption(option)
  })
}

// 显示状态码分布
const showStatusDistribution = () => {
  statusDistributionVisible.value = true
  setTimeout(() => {
    if (!statusChart) {
      statusChart = echarts.init(statusChartRef.value)
    }
    
    const option = {
      tooltip: {
        trigger: 'item',
        formatter: '{b}: {c} ({d}%)'
      },
      legend: {
        orient: 'vertical',
        left: 'left'
      },
      series: [
        {
          type: 'pie',
          radius: '70%',
          data: [
            { value: 850, name: '200', itemStyle: { color: '#67C23A' } },
            { value: 50, name: '404', itemStyle: { color: '#E6A23C' } },
            { value: 30, name: '500', itemStyle: { color: '#F56C6C' } },
            { value: 20, name: '403', itemStyle: { color: '#909399' } }
          ],
          emphasis: {
            itemStyle: {
              shadowBlur: 10,
              shadowOffsetX: 0,
              shadowColor: 'rgba(0, 0, 0, 0.5)'
            }
          }
        }
      ]
    }
    
    statusChart.setOption(option)
  })
}

// 监听弹窗关闭，销毁图表实例
watch([logDistributionVisible, statusDistributionVisible], ([newLogVisible, newStatusVisible], [oldLogVisible, oldStatusVisible]) => {
  if (!newLogVisible && oldLogVisible) {
    logChart?.dispose()
    logChart = null
  }
  if (!newStatusVisible && oldStatusVisible) {
    statusChart?.dispose()
    statusChart = null
  }
})

// 详情弹窗相关
const detailVisible = ref(false)
const detailData = ref([])

// 处理详情点击
const handleDetailClick = (row) => {
  showFullDetail.value = true
  httpDetailVisible.value = true
  
  // 设置基本信息
  httpDetail.method = row.method || 'POST'
  httpDetail.path = row.url
  httpDetail.source = row.message
  httpDetail.timestamp = row.timestamp
  httpDetail.status = row.statusCode
  httpDetail.duration = row.duration

  // 设置请求参数
  httpDetail.requestBody = JSON.stringify({
    userId: row.userId,
    pageSize: 10,
    pageNum: 1,
    queryTime: row.timestamp
  }, null, 2)

  // 设置返回结果
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

  // 设置请求头
  httpDetail.requestHeaders = JSON.stringify({
    "Content-Type": "application/json",
    "X-Request-ID": row.traceId,
    "Authorization": "Bearer xxxxxxxx",
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
    "X-Response-Time": `${row.duration}ms`
  }, null, 2)

  // 重置展开状态
  httpDetail.showRequestHeaders = false
  httpDetail.showResponseHeaders = false
}

// 切换展开/收起状态
const toggleExpand = (item) => {
  item.expanded = !item.expanded
}

// HTTP详情弹窗相关
const httpDetailVisible = ref(false)
const showFullDetail = ref(false)
const httpDetail = reactive({
  method: '',
  path: '',
  source: '',
  timestamp: '',
  status: 200,
  duration: '',
  requestBody: '',
  responseBody: '',
  requestHeaders: '',
  responseHeaders: '',
  showRequestHeaders: false,
  showResponseHeaders: false
})

// 处理URL点击
const handleUrlClick = (row) => {
  showFullDetail.value = false
  httpDetailVisible.value = true
  
  // 设置请求参数
  httpDetail.requestBody = JSON.stringify({
    userId: row.userId,
    pageSize: 10,
    pageNum: 1,
    queryTime: row.timestamp
  }, null, 2)

  // 设置返回结果
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

  // 清空其他信息
  httpDetail.method = ''
  httpDetail.path = ''
  httpDetail.source = ''
  httpDetail.timestamp = ''
  httpDetail.status = 200
  httpDetail.duration = ''
  httpDetail.requestHeaders = ''
  httpDetail.responseHeaders = ''
  httpDetail.showRequestHeaders = false
  httpDetail.showResponseHeaders = false
}

// 处理链路中的HTTP详情点击
const showHttpDetail = (item) => {
  httpDetailVisible.value = true
  
  // 从detail中解析完整的请求信息
  let requestData = {}
  let responseData = {}
  
  try {
    // 尝试解析完整的detail信息
    const detailLines = item.detail?.split('\n') || []
    let currentSection = null
    let currentData = []
    
    for (const line of detailLines) {
      if (line.includes('Request Headers:')) {
        currentSection = 'headers'
        currentData = []
      } else if (line.includes('Request Body:')) {
        currentSection = 'body'
        currentData = []
      } else if (line.includes('Response:')) {
        currentSection = 'response'
        currentData = []
      } else if (line.trim()) {
        currentData.push(line)
      }
      
      if (currentSection === 'headers' && currentData.length > 0) {
        requestData.headers = currentData.join('\n')
      } else if (currentSection === 'body' && currentData.length > 0) {
        requestData.body = currentData.join('\n')
      } else if (currentSection === 'response' && currentData.length > 0) {
        responseData = currentData.join('\n')
      }
    }
  } catch (e) {
    console.error('解析detail失败:', e)
  }

  // 设置请求参数
  httpDetail.requestBody = requestData.body || JSON.stringify({
    method: item.name.split(' ')[0],
    path: item.name.split(' ')[1],
    timestamp: new Date().toISOString(),
    duration: item.duration,
    traceId: item.id
  }, null, 2)

  // 设置返回结果
  try {
    httpDetail.responseBody = responseData || JSON.stringify({
      code: 200,
      message: "success",
      data: {
        traceId: item.id,
        status: "COMPLETED",
        duration: item.duration,
        timestamp: new Date().toISOString()
      }
    }, null, 2)
  } catch (e) {
    httpDetail.responseBody = responseData
  }

  // 设置请求头
  try {
    httpDetail.requestHeaders = requestData.headers || JSON.stringify({
      "Content-Type": "application/json",
      "X-Request-ID": item.id,
      "X-Trace-ID": item.id,
      "User-Agent": "Mozilla/5.0",
      "Accept": "application/json",
      "Accept-Language": "zh-CN,zh;q=0.9",
      "Accept-Encoding": "gzip, deflate, br",
      "Connection": "keep-alive"
    }, null, 2)
  } catch (e) {
    httpDetail.requestHeaders = requestData.headers
  }

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
    "X-Response-Time": `${item.duration}ms`
  }, null, 2)
}
</script>

<style scoped>
.gateway-logs {
  padding: 16px;
  height: 100%;
  display: flex;
  flex-direction: column;
  gap: 16px;
  background: #f5f7fa;
}

.search-area {
  background: white;
  padding: 12px 24px;
  border-radius: 4px;
}

.search-form {
  display: flex;
  align-items: center;
  flex-wrap: nowrap;
  gap: 16px;
}

.no-margin {
  margin: 0 !important;
}

.operation-buttons {
  margin-left: auto !important;
  white-space: nowrap;
  margin-right: 8px !important;
}

.list-header {
  margin-bottom: 8px;
  font-size: 12px;
  color: #666;
}

.log-list {
  flex: 1;
  display: flex;
  flex-direction: column;
}

:deep(.el-table) {
  font-size: 12px;
}

:deep(.el-table th) {
  background-color: #f5f7fa;
  color: #606266;
  font-weight: 500;
  font-size: 12px;
}

.success-status {
  color: #67c23a;
}

.operation-cell {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 2px;
}

:deep(.el-button--text) {
  color: #409EFF;
  font-size: 12px;
  padding: 0;
  height: auto;
  min-height: unset;
}

:deep(.el-divider--vertical) {
  height: 12px;
  margin: 0 2px;
}

:deep(.el-form-item__label) {
  font-size: 12px;
  color: #606266;
  padding-right: 12px !important;
}

:deep(.el-input__wrapper) {
  padding-left: 8px;
  padding-right: 8px;
}

:deep(.el-form--inline .el-form-item__content) {
  margin-right: 0;
}

:deep(.el-button--small) {
  padding: 5px 11px;
}

:deep(.el-button.is-circle) {
  margin-left: 8px;
}

:deep(.el-input-group__append) {
  padding: 0 8px;
}

:deep(.el-dialog__body) {
  padding: 20px;
}

:deep(.el-tooltip__trigger) {
  margin-left: 8px;
}

.detail-content {
  padding: 0 16px;
}

.trace-item {
  margin-bottom: 8px;
}

.trace-header {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px;
  background: #fafafa;
  border-radius: 2px;
  cursor: pointer;
  user-select: none;
}

.trace-header:hover {
  background: #f0f0f0;
}

.expand-icon {
  width: 16px;
  height: 16px;
  line-height: 14px;
  text-align: center;
  border: 1px solid #d9d9d9;
  border-radius: 2px;
  font-size: 12px;
  color: #666;
}

.trace-type {
  padding: 2px 6px;
  border-radius: 2px;
  font-size: 12px;
  font-weight: 500;
}

.trace-type.HTTP {
  background-color: #e6f7ff;
  color: #1890ff;
}

.trace-type.RPC {
  background-color: #f6ffed;
  color: #52c41a;
}

.trace-type.SQL {
  background-color: #fff7e6;
  color: #fa8c16;
}

.trace-type.Redis {
  background-color: #fff1f0;
  color: #f5222d;
}

.trace-type.Process {
  background-color: #f9f0ff;
  color: #722ed1;
}

.trace-name {
  flex: 1;
  font-size: 13px;
  color: #333;
}

.trace-duration {
  font-size: 12px;
  color: #999;
  margin-left: 8px;
}

.trace-detail {
  margin: 4px 0 4px 32px;
  padding: 8px 12px;
  background: #fafafa;
  border-radius: 2px;
  font-size: 12px;
  font-family: monospace;
}

.trace-detail pre {
  margin: 0;
  white-space: pre-wrap;
  word-break: break-all;
}

.trace-children {
  margin-top: 4px;
}

.http-detail-dialog :deep(.el-dialog__header) {
  border-bottom: 1px solid #ebeef5;
  margin-right: 0;
  padding: 16px 20px;
}

.http-detail-dialog :deep(.el-dialog__title) {
  font-size: 16px;
  font-weight: 600;
}

.section {
  border: 1px solid #ebeef5;
  border-radius: 6px;
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.05);
  overflow: hidden;
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
}

:deep(.el-button--primary.is-link:hover) {
  color: #40a9ff;
}

.trace-name :deep(.el-button--primary.is-link) {
  font-size: 13px;
  font-weight: normal;
}

.info-item {
  display: flex;
  align-items: center;
  margin-bottom: 8px;
  font-size: 13px;
  line-height: 1.6;
}

.info-item:last-child {
  margin-bottom: 0;
}

.info-item .label {
  width: 80px;
  color: #666;
  flex-shrink: 0;
}

.info-item .value {
  color: #333;
}

.info-item .value.method {
  color: #1890ff;
  font-weight: 500;
}

.info-item .value.status {
  font-weight: 500;
}

.info-item .value.status.success {
  color: #52c41a;
}

.info-item .value.status.error {
  color: #f5222d;
}

.info-item .value.duration {
  color: #722ed1;
  font-weight: 500;
}

.info-item .value.source {
  color: #fa8c16;
  font-weight: 500;
}
</style> 