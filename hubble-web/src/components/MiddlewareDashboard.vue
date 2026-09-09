<template>
  <div class="middleware-page">
    <div class="page-header">
      <h2>中间件监控</h2>
      <div class="header-actions">
        <el-radio-group v-model="activeTab" size="small" @change="fetchData">
          <el-radio-button value="redis">Redis</el-radio-button>
          <el-radio-button value="mysql">MySQL / PolarDB</el-radio-button>
          <el-radio-button value="rocketmq">RocketMQ</el-radio-button>
          <el-radio-button value="kafka">Kafka</el-radio-button>
          <el-radio-button value="lindorm">Lindorm</el-radio-button>
          <el-radio-button value="elasticsearch">Elasticsearch</el-radio-button>
          <el-radio-button value="oss">OSS</el-radio-button>
          <el-radio-button value="pod">Pod</el-radio-button>
          <el-radio-button value="node">Node</el-radio-button>
          <el-radio-button value="jvm">JVM</el-radio-button>
          <el-radio-button value="threadpool">线程池</el-radio-button>
        </el-radio-group>
        <el-button size="small" @click="fetchData" :loading="loading">刷新</el-button>
        <el-button size="small" @click="handleExport">导出</el-button>
      </div>
    </div>

    <!-- 告警摘要 -->
    <div v-if="alertSummary.total > 0 && !['pod','node','jvm','threadpool'].includes(activeTab)" class="alert-summary-bar">
      <el-alert
        v-if="alertSummary.redCount > 0"
        :title="`${alertSummary.redCount} 个实例红盘告警`"
        type="error" show-icon :closable="false" style="flex:1" />
      <el-alert
        v-if="alertSummary.yellowCount > 0"
        :title="`${alertSummary.yellowCount} 个实例粉盘告警`"
        type="warning" show-icon :closable="false" style="flex:1" />
      <el-alert
        v-if="alertSummary.redCount === 0 && alertSummary.yellowCount === 0"
        title="所有实例状态正常"
        type="success" show-icon :closable="false" style="flex:1" />
    </div>

    <!-- Redis 监控 -->
    <div v-if="activeTab === 'redis'" class="monitor-section">
      <el-table v-loading="loading" :data="redisData" stripe border size="small" style="width: 100%">
        <el-table-column label="告警" width="70" align="center">
          <template #default="{ row }">
            <span :class="'alert-dot-' + (row.alertLevel || 'normal')"></span>
          </template>
        </el-table-column>
        <el-table-column label="实例ID" width="200" show-overflow-tooltip prop="instanceId" />
        <el-table-column label="实例名称" width="160" show-overflow-tooltip prop="instanceName" />
        <el-table-column label="规格" width="120" prop="instanceType" />
        <el-table-column label="状态" width="80" align="center">
          <template #default="{ row }">
            <el-tag :type="row.status === 'Normal' ? 'success' : 'warning'" size="small">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="CPU%" width="90" align="right">
          <template #default="{ row }">
            <span :class="alertMetricClass(row, 'cpuUsage')">{{ num(row.cpuUsage).toFixed(1) }}%</span>
          </template>
        </el-table-column>
        <el-table-column label="连接数" width="90" align="right">
          <template #default="{ row }">{{ Math.round(num(row.connections)) }}</template>
        </el-table-column>
        <el-table-column label="内存%" width="90" align="right">
          <template #default="{ row }">
            <span :class="alertMetricClass(row, 'memoryUsage')">{{ num(row.memoryUsage).toFixed(1) }}%</span>
          </template>
        </el-table-column>
        <el-table-column label="QPS" width="90" align="right">
          <template #default="{ row }">{{ Math.round(num(row.qps)) }}</template>
        </el-table-column>
      </el-table>

      <div class="sub-section">
        <el-tabs v-model="redisSubTab" type="card" size="small">
          <el-tab-pane label="Big Keys Top10" name="bigKeys">
            <el-table :data="redisBigKeysData" stripe border size="small" style="width: 100%">
              <el-table-column label="#" width="50" prop="rank" align="center" />
              <el-table-column label="Key" min-width="250" show-overflow-tooltip prop="key" />
              <el-table-column label="类型" width="80" prop="type" />
              <el-table-column label="说明" width="150" show-overflow-tooltip prop="description" />
              <el-table-column label="内存" width="110" align="right">
                <template #default="{ row }">{{ formatBytes(row.memoryBytes) }}</template>
              </el-table-column>
              <el-table-column label="TTL" width="90" align="right">
                <template #default="{ row }">{{ row.ttl > 0 ? row.ttl + 's' : '-' }}</template>
              </el-table-column>
            </el-table>
          </el-tab-pane>
          <el-tab-pane label="慢查询 Top10" name="slowQueries">
            <el-alert v-if="redisSlowQueriesData.length === 0" type="info" :closable="false" show-icon style="margin-bottom: 12px">
              <template #title>最近 24 小时无 Redis 慢查询日志</template>
              慢查询数据从 SLS 日志中获取（应用级别）
            </el-alert>
            <el-table v-else :data="redisSlowQueriesData" stripe border size="small" style="width: 100%">
              <el-table-column label="#" width="50" prop="rank" align="center" />
              <el-table-column label="服务" width="180" show-overflow-tooltip prop="instanceName" />
              <el-table-column label="耗时" width="120" align="right">
                <template #default="{ row }">{{ (num(row.durationMicros) / 1000).toFixed(1) }} ms</template>
              </el-table-column>
              <el-table-column label="Keys数" width="100" align="right">
                <template #default="{ row }">{{ num(row.keysCount) }}</template>
              </el-table-column>
              <el-table-column label="时间" min-width="180">
                <template #default="{ row }">{{ formatTime(row.timestamp) }}</template>
              </el-table-column>
            </el-table>
          </el-tab-pane>
        </el-tabs>
      </div>
      <el-empty v-if="!loading && redisData.length === 0" description="暂无 Redis 实例" />
    </div>

    <!-- MySQL/PolarDB 监控 -->
    <div v-if="activeTab === 'mysql'" class="monitor-section">
      <el-table v-loading="loading" :data="mysqlData" stripe border size="small" style="width: 100%">
        <el-table-column label="告警" width="70" align="center">
          <template #default="{ row }">
            <span :class="'alert-dot-' + (row.alertLevel || 'normal')"></span>
          </template>
        </el-table-column>
        <el-table-column label="实例ID" width="200" show-overflow-tooltip prop="instanceId" />
        <el-table-column label="实例名称" width="160" show-overflow-tooltip prop="instanceName" />
        <el-table-column label="引擎" width="90" prop="engine" />
        <el-table-column label="版本" width="80" prop="engineVersion" />
        <el-table-column label="状态" width="80" align="center">
          <template #default="{ row }">
            <el-tag :type="row.status === 'Running' ? 'success' : 'warning'" size="small">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="CPU%" width="90" align="right">
          <template #default="{ row }">
            <span :class="alertMetricClass(row, 'cpuUsage')">{{ num(row.cpuUsage).toFixed(1) }}%</span>
          </template>
        </el-table-column>
        <el-table-column label="连接数" width="90" align="right">
          <template #default="{ row }">{{ Math.round(num(row.connections)) }}</template>
        </el-table-column>
        <el-table-column label="IOPS" width="80" align="right">
          <template #default="{ row }">{{ Math.round(num(row.iops)) }}</template>
        </el-table-column>
        <el-table-column label="磁盘%" width="90" align="right">
          <template #default="{ row }">
            <span :class="alertMetricClass(row, 'diskUsage')">{{ num(row.diskUsage).toFixed(1) }}%</span>
          </template>
        </el-table-column>
      </el-table>

      <div class="sub-section">
        <el-tabs v-model="mysqlSubTab" type="card" size="small">
          <el-tab-pane label="慢查询 Top10" name="slowQueries">
            <el-alert v-if="mysqlSlowQueriesData.length === 0" type="info" :closable="false" show-icon style="margin-bottom: 12px">
              <template #title>最近 1 小时无 MySQL 慢查询</template>
              慢查询数据从 ARMS 链路追踪中获取（SQL span 耗时 ≥ 10ms），当前未发现慢查询
            </el-alert>
            <el-table v-else :data="mysqlSlowQueriesData" stripe border size="small" style="width: 100%">
              <el-table-column label="#" width="50" prop="rank" align="center" />
              <el-table-column label="服务" width="180" show-overflow-tooltip prop="instanceName" />
              <el-table-column label="SQL" min-width="300" show-overflow-tooltip prop="sql" />
              <el-table-column label="耗时" width="120" align="right">
                <template #default="{ row }">{{ num(row.durationMs).toFixed(0) }} ms</template>
              </el-table-column>
              <el-table-column label="时间" min-width="180">
                <template #default="{ row }">{{ formatTime(row.timestamp) }}</template>
              </el-table-column>
            </el-table>
          </el-tab-pane>
          <el-tab-pane label="DB 分库监控" name="dbShard">
            <el-table :data="dbData" stripe border size="small" style="width: 100%">
              <el-table-column label="排名" width="60" type="index" />
              <el-table-column label="实例" min-width="250" show-overflow-tooltip prop="instanceName" />
              <el-table-column label="引擎" width="100" prop="engine" />
              <el-table-column label="CPU%" width="100" align="right">
                <template #default="{ row }">
                  <span :class="metricClass(row.cpuUsage)">{{ num(row.cpuUsage).toFixed(1) }}%</span>
                </template>
              </el-table-column>
              <el-table-column label="内存%" width="100" align="right">
                <template #default="{ row }">
                  <span :class="metricClass(row.memoryUsage)">{{ num(row.memoryUsage).toFixed(1) }}%</span>
                </template>
              </el-table-column>
              <el-table-column label="IOPS%" width="100" align="right">
                <template #default="{ row }">{{ num(row.iops).toFixed(1) }}%</template>
              </el-table-column>
              <el-table-column label="活跃会话" width="120" align="right">
                <template #default="{ row }">{{ num(row.activeSessions).toFixed(1) }}</template>
              </el-table-column>
            </el-table>
          </el-tab-pane>
          <el-tab-pane label="Druid 连接池" name="druid">
            <el-table :data="druidData" stripe border size="small" style="width: 100%">
              <el-table-column label="排名" width="60" type="index" />
              <el-table-column label="应用" min-width="200" show-overflow-tooltip prop="application" />
              <el-table-column label="活动连接" width="110" align="right" prop="activeCount" />
              <el-table-column label="最大连接" width="110" align="right" prop="maxActive" />
              <el-table-column label="使用率%" width="110" align="right">
                <template #default="{ row }">
                  <span :class="metricClass(row.usageRate)">{{ num(row.usageRate).toFixed(1) }}%</span>
                </template>
              </el-table-column>
              <el-table-column label="等待线程" width="110" align="right" prop="waitThreadCount" />
              <el-table-column label="SQL执行/s" width="120" align="right">
                <template #default="{ row }">{{ num(row.sqlExecuteRate).toFixed(1) }}</template>
              </el-table-column>
            </el-table>
          </el-tab-pane>
        </el-tabs>
      </div>
      <el-empty v-if="!loading && mysqlData.length === 0" description="暂无 MySQL/PolarDB 实例" />
    </div>

    <!-- RocketMQ 监控 -->
    <div v-if="activeTab === 'rocketmq'" class="monitor-section">
      <div class="sub-title">实例基础信息</div>
      <el-table v-loading="loading" :data="rocketmqData" stripe border size="small" style="width: 100%">
        <el-table-column label="告警" width="70" align="center">
          <template #default="{ row }">
            <span :class="'alert-dot-' + (row.alertLevel || 'normal')"></span>
          </template>
        </el-table-column>
        <el-table-column label="实例ID" width="220" show-overflow-tooltip prop="instanceId" />
        <el-table-column label="实例名称" width="150" show-overflow-tooltip prop="instanceName" />
        <el-table-column label="地域" width="130" show-overflow-tooltip prop="region" />
        <el-table-column label="状态" width="80" align="center">
          <template #default="{ row }">
            <el-tag :type="row.status === 'Running' ? 'success' : 'warning'" size="small">{{ row.status || '-' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="版本" width="70" align="center" prop="version" />
        <el-table-column label="消息堆积" width="110" align="right">
          <template #default="{ row }">
            <span :class="alertMetricClass(row, 'messageAccumulation')">
              {{ Math.round(num(row.messageAccumulation)).toLocaleString() }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="生产 TPS" width="100" align="right">
          <template #default="{ row }">{{ num(row.sendTps).toFixed(1) }}</template>
        </el-table-column>
        <el-table-column label="消费 TPS" width="100" align="right">
          <template #default="{ row }">{{ num(row.consumeTps).toFixed(1) }}</template>
        </el-table-column>
      </el-table>

      <div class="sub-section">
        <div class="sub-title">Top Topics（按消息堆积排序）</div>
        <el-table :data="rocketmqTopTopicsData" stripe border size="small" style="width: 100%">
          <el-table-column label="#" width="50" prop="rank" align="center" />
          <el-table-column label="Topic" min-width="200" show-overflow-tooltip prop="topic" />
          <el-table-column label="实例" width="150" show-overflow-tooltip prop="instanceName" />
          <el-table-column label="消息堆积" width="120" align="right">
            <template #default="{ row }">{{ Math.round(num(row.messageAccumulation)).toLocaleString() }}</template>
          </el-table-column>
          <el-table-column label="生产 TPS" width="110" align="right">
            <template #default="{ row }">{{ num(row.sendTps).toFixed(1) }}</template>
          </el-table-column>
          <el-table-column label="消费 TPS" width="110" align="right">
            <template #default="{ row }">{{ num(row.consumeTps).toFixed(1) }}</template>
          </el-table-column>
        </el-table>
      </div>
      <el-empty v-if="!loading && rocketmqData.length === 0" description="暂无 RocketMQ 实例" />
    </div>

    <!-- Kafka 监控 -->
    <div v-if="activeTab === 'kafka'" class="monitor-section">
      <div class="sub-title">实例基础信息</div>
      <el-table v-loading="loading" :data="kafkaData" stripe border size="small" style="width: 100%">
        <el-table-column label="告警" width="70" align="center">
          <template #default="{ row }">
            <span :class="'alert-dot-' + (row.alertLevel || 'normal')"></span>
          </template>
        </el-table-column>
        <el-table-column label="实例ID" width="220" show-overflow-tooltip prop="instanceId" />
        <el-table-column label="实例名称" width="150" show-overflow-tooltip prop="instanceName" />
        <el-table-column label="地域" width="130" show-overflow-tooltip prop="region" />
        <el-table-column label="状态" width="80" align="center">
          <template #default="{ row }">
            <el-tag :type="row.status === 'Running' ? 'success' : 'warning'" size="small">{{ row.status || '-' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="版本" width="70" align="center" prop="version" />
        <el-table-column label="Lag" width="110" align="right">
          <template #default="{ row }">
            <span :class="alertMetricClass(row, 'lag')">
              {{ Math.round(num(row.lag)).toLocaleString() }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="生产 TPS" width="100" align="right">
          <template #default="{ row }">{{ num(row.produceTps).toFixed(1) }}</template>
        </el-table-column>
        <el-table-column label="消费 TPS" width="100" align="right">
          <template #default="{ row }">{{ num(row.consumeTps).toFixed(1) }}</template>
        </el-table-column>
      </el-table>

      <div class="sub-section">
        <div class="sub-title">Top Partitions（按 Lag 排序）</div>
        <el-table :data="kafkaTopPartitionsData" stripe border size="small" style="width: 100%">
          <el-table-column label="#" width="50" prop="rank" align="center" />
          <el-table-column label="Topic" min-width="200" show-overflow-tooltip prop="topic" />
          <el-table-column label="实例" width="150" show-overflow-tooltip prop="instanceName" />
          <el-table-column label="Lag" width="120" align="right">
            <template #default="{ row }">{{ Math.round(num(row.lag)).toLocaleString() }}</template>
          </el-table-column>
          <el-table-column label="生产 TPS" width="110" align="right">
            <template #default="{ row }">{{ num(row.produceTps).toFixed(1) }}</template>
          </el-table-column>
          <el-table-column label="消费 TPS" width="110" align="right">
            <template #default="{ row }">{{ num(row.consumeTps).toFixed(1) }}</template>
          </el-table-column>
        </el-table>
      </div>
      <el-empty v-if="!loading && kafkaData.length === 0" description="暂无 Kafka 实例" />
    </div>

    <!-- Lindorm 监控 -->
    <div v-if="activeTab === 'lindorm'" class="monitor-section">
      <el-table v-loading="loading" :data="lindormData" stripe border size="small" style="width: 100%">
        <el-table-column label="告警" width="70" align="center">
          <template #default="{ row }">
            <span :class="'alert-dot-' + (row.alertLevel || 'normal')"></span>
          </template>
        </el-table-column>
        <el-table-column label="实例ID" width="220" show-overflow-tooltip prop="instanceId" />
        <el-table-column label="实例名称" min-width="160" show-overflow-tooltip prop="instanceName" />
        <el-table-column label="CPU%" width="100" align="right">
          <template #default="{ row }">
            <span :class="alertMetricClass(row, 'cpuUsage')">{{ num(row.cpuUsage).toFixed(1) }}%</span>
          </template>
        </el-table-column>
        <el-table-column label="IOWait%" width="100" align="right">
          <template #default="{ row }">
            <span :style="{ color: num(row.cpuWio) > 10 ? '#f56c6c' : num(row.cpuWio) > 5 ? '#e6a23c' : '' }">{{ num(row.cpuWio).toFixed(2) }}%</span>
          </template>
        </el-table-column>
        <el-table-column label="热存储%" width="100" align="right">
          <template #default="{ row }">
            <span :class="alertMetricClass(row, 'diskUsage')">{{ num(row.hotStorageUsedPercent).toFixed(1) }}%</span>
          </template>
        </el-table-column>
        <el-table-column label="读 QPS" width="100" align="right">
          <template #default="{ row }">{{ Math.round(num(row.qps)).toLocaleString() }}</template>
        </el-table-column>
        <el-table-column label="写 QPS" width="100" align="right">
          <template #default="{ row }">{{ Math.round(num(row.writeQps)).toLocaleString() }}</template>
        </el-table-column>
        <el-table-column label="读 RT" width="90" align="right">
          <template #default="{ row }">
            <span :style="{ color: num(row.readRt) > 10 ? '#f56c6c' : num(row.readRt) > 5 ? '#e6a23c' : '' }">{{ num(row.readRt).toFixed(2) }}ms</span>
          </template>
        </el-table-column>
        <el-table-column label="写 RT" width="90" align="right">
          <template #default="{ row }">
            <span :style="{ color: num(row.writeRt) > 10 ? '#f56c6c' : num(row.writeRt) > 5 ? '#e6a23c' : '' }">{{ num(row.writeRt).toFixed(2) }}ms</span>
          </template>
        </el-table-column>
        <el-table-column label="网络流入/流出" width="160" align="right">
          <template #default="{ row }">
            <span>{{ formatBytes(row.bytesIn) }}/s</span>
            <span style="color: #909399; margin: 0 4px">/</span>
            <span>{{ formatBytes(row.bytesOut) }}/s</span>
          </template>
        </el-table-column>
      </el-table>

      <div class="sub-section">
        <div class="sub-title">Top 实例详情（按 QPS 排序）</div>
        <el-table :data="lindormTopTablesData" stripe border size="small" style="width: 100%">
          <el-table-column label="#" width="50" prop="rank" align="center" />
          <el-table-column label="实例名称" min-width="180" show-overflow-tooltip prop="tableName" />
          <el-table-column label="CPU%" width="90" align="right">
            <template #default="{ row }">{{ num(row.cpuUsage).toFixed(1) }}%</template>
          </el-table-column>
          <el-table-column label="读 QPS" width="100" align="right">
            <template #default="{ row }">{{ Math.round(num(row.readQps)).toLocaleString() }}</template>
          </el-table-column>
          <el-table-column label="写 QPS" width="100" align="right">
            <template #default="{ row }">{{ Math.round(num(row.writeQps)).toLocaleString() }}</template>
          </el-table-column>
          <el-table-column label="读 RT" width="90" align="right">
            <template #default="{ row }">{{ num(row.readRt).toFixed(2) }}ms</template>
          </el-table-column>
          <el-table-column label="写 RT" width="90" align="right">
            <template #default="{ row }">{{ num(row.writeRt).toFixed(2) }}ms</template>
          </el-table-column>
          <el-table-column label="热存储%" width="100" align="right">
            <template #default="{ row }">{{ num(row.hotStorageUsedPercent).toFixed(1) }}%</template>
          </el-table-column>
          <el-table-column label="热存储" width="110" align="right">
            <template #default="{ row }">{{ formatBytes(row.hotStorageUsedBytes) }}</template>
          </el-table-column>
          <el-table-column label="Compaction" width="110" align="right">
            <template #default="{ row }">
              <span :style="{ color: num(row.compactionQueueSize) > 10 ? '#f56c6c' : num(row.compactionQueueSize) > 5 ? '#e6a23c' : '' }">{{ Math.round(num(row.compactionQueueSize)) }}</span>
            </template>
          </el-table-column>
        </el-table>
      </div>
      <el-empty v-if="!loading && lindormData.length === 0" description="暂无 Lindorm 实例" />
    </div>

    <!-- Elasticsearch 监控 -->
    <div v-if="activeTab === 'elasticsearch'" class="monitor-section">
      <el-table v-loading="loading" :data="esData" stripe border size="small" style="width: 100%">
        <el-table-column label="告警" width="70" align="center">
          <template #default="{ row }">
            <span :class="'alert-dot-' + (row.alertLevel || 'normal')"></span>
          </template>
        </el-table-column>
        <el-table-column label="实例ID" width="220" show-overflow-tooltip prop="instanceId" />
        <el-table-column label="实例名称" min-width="180" show-overflow-tooltip prop="instanceName" />
        <el-table-column label="CPU%" width="120" align="right">
          <template #default="{ row }">
            <span :class="alertMetricClass(row, 'cpuUsage')">{{ num(row.cpuUsage).toFixed(1) }}%</span>
          </template>
        </el-table-column>
        <el-table-column label="磁盘%" width="120" align="right">
          <template #default="{ row }">
            <span :class="alertMetricClass(row, 'diskUsage')">{{ num(row.diskUsage).toFixed(1) }}%</span>
          </template>
        </el-table-column>
        <el-table-column label="JVM 内存%" width="130" align="right">
          <template #default="{ row }">
            <span :class="alertMetricClass(row, 'jvmMemory')">{{ num(row.jvmMemory).toFixed(1) }}%</span>
          </template>
        </el-table-column>
      </el-table>

      <div class="sub-section">
        <div class="sub-title">Top 大索引（按存储排序）</div>
        <el-table :data="esTopIndicesData" stripe border size="small" style="width: 100%">
          <el-table-column label="#" width="50" prop="rank" align="center" />
          <el-table-column label="索引名" min-width="220" show-overflow-tooltip prop="indexName" />
          <el-table-column label="说明" width="150" show-overflow-tooltip prop="description" />
          <el-table-column label="文档数" width="120" align="right">
            <template #default="{ row }">{{ Number(num(row.docCount)).toLocaleString() }}</template>
          </el-table-column>
          <el-table-column label="存储" width="100" align="right">
            <template #default="{ row }">{{ num(row.storageGB).toFixed(0) }} GB</template>
          </el-table-column>
          <el-table-column label="分片" width="80" align="right" prop="shardCount" />
          <el-table-column label="副本" width="80" align="right" prop="replicaCount" />
        </el-table>
      </div>
      <el-empty v-if="!loading && esData.length === 0" description="暂无 Elasticsearch 实例" />
    </div>

    <!-- OSS 监控 -->
    <div v-if="activeTab === 'oss'" class="monitor-section">
      <el-table v-loading="loading" :data="ossData" stripe border size="small" style="width: 100%">
        <el-table-column label="告警" width="70" align="center">
          <template #default="{ row }">
            <span :class="'alert-dot-' + (row.alertLevel || 'normal')"></span>
          </template>
        </el-table-column>
        <el-table-column label="Bucket" width="220" show-overflow-tooltip prop="bucketName" />
        <el-table-column label="名称" min-width="160" show-overflow-tooltip prop="instanceName" />
        <el-table-column label="请求数" width="110" align="right">
          <template #default="{ row }">{{ Math.round(num(row.totalRequests)).toLocaleString() }}</template>
        </el-table-column>
        <el-table-column label="4xx 错误率" width="110" align="right">
          <template #default="{ row }">
            <span :class="alertMetricClass(row, 'errorRate4xx')">{{ num(row.errorRate4xx).toFixed(2) }}%</span>
          </template>
        </el-table-column>
        <el-table-column label="5xx 错误率" width="110" align="right">
          <template #default="{ row }">
            <span :class="alertMetricClass(row, 'errorRate5xx')">{{ num(row.errorRate5xx).toFixed(2) }}%</span>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!loading && ossData.length === 0" description="暂无 OSS Bucket" />
    </div>

    <!-- Pod 监控 -->
    <div v-if="activeTab === 'pod'" class="monitor-section">
      <div class="pod-grid">
        <div class="pod-card">
          <div class="card-title">Pod CPU Top10</div>
          <el-table v-loading="loading" :data="podCpuData" stripe size="small" style="width: 100%">
            <el-table-column label="排名" width="60" type="index" />
            <el-table-column label="Pod" min-width="250" show-overflow-tooltip prop="pod" />
            <el-table-column label="CPU" width="100" align="right">
              <template #default="{ row }">{{ num(row.cpu).toFixed(3) }}</template>
            </el-table-column>
          </el-table>
        </div>
        <div class="pod-card">
          <div class="card-title">Pod 内存 Top10</div>
          <el-table v-loading="loading" :data="podMemData" stripe size="small" style="width: 100%">
            <el-table-column label="排名" width="60" type="index" />
            <el-table-column label="Pod" min-width="250" show-overflow-tooltip prop="pod" />
            <el-table-column label="内存(MB)" width="100" align="right">
              <template #default="{ row }">{{ row.memoryMB }}</template>
            </el-table-column>
          </el-table>
        </div>
      </div>
      <el-empty v-if="!loading && podCpuData.length === 0 && podMemData.length === 0" description="暂无 Pod 数据" />
    </div>

    <!-- Node 监控 -->
    <div v-if="activeTab === 'node'" class="monitor-section">
      <el-table v-loading="loading" :data="nodeData" stripe border size="small" style="width: 100%">
        <el-table-column label="排名" width="60" type="index" />
        <el-table-column label="节点" min-width="300" show-overflow-tooltip prop="node" />
        <el-table-column label="CPU%" width="120" align="right">
          <template #default="{ row }">
            <span :class="metricClass(row.cpuUsage)">{{ num(row.cpuUsage).toFixed(1) }}%</span>
          </template>
        </el-table-column>
        <el-table-column label="内存%" width="120" align="right">
          <template #default="{ row }">
            <span :class="metricClass(row.memoryUsage)">{{ num(row.memoryUsage).toFixed(1) }}%</span>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!loading && nodeData.length === 0" description="暂无 Node 数据" />
    </div>

    <!-- JVM 监控 -->
    <div v-if="activeTab === 'jvm'" class="monitor-section">
      <el-table v-loading="loading" :data="jvmData" stripe border size="small" style="width: 100%">
        <el-table-column label="排名" width="60" type="index" />
        <el-table-column label="应用" min-width="200" show-overflow-tooltip prop="application" />
        <el-table-column label="堆内存%" width="110" align="right">
          <template #default="{ row }">
            <span :class="metricClass(row.heapUsage)">{{ num(row.heapUsage).toFixed(1) }}%</span>
          </template>
        </el-table-column>
        <el-table-column label="GC/s" width="100" align="right">
          <template #default="{ row }">{{ num(row.gcRate).toFixed(2) }}</template>
        </el-table-column>
        <el-table-column label="QPS" width="110" align="right">
          <template #default="{ row }">{{ num(row.qps).toFixed(1) }}</template>
        </el-table-column>
        <el-table-column label="CPU%" width="100" align="right">
          <template #default="{ row }">
            <span :class="metricClass(row.cpuUsage)">{{ num(row.cpuUsage).toFixed(1) }}%</span>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!loading && jvmData.length === 0" description="暂无 JVM 数据" />
    </div>

    <!-- 线程池监控 -->
    <div v-if="activeTab === 'threadpool'" class="monitor-section">
      <el-table v-loading="loading" :data="threadPoolData" stripe border size="small" style="width: 100%">
        <el-table-column label="排名" width="60" type="index" />
        <el-table-column label="应用" min-width="160" show-overflow-tooltip prop="application" />
        <el-table-column label="线程池" min-width="200" show-overflow-tooltip prop="threadPoolName" />
        <el-table-column label="活跃线程" width="100" align="right" prop="activeCount" />
        <el-table-column label="最大线程" width="100" align="right" prop="maxSize" />
        <el-table-column label="使用率%" width="100" align="right">
          <template #default="{ row }">
            <span :class="metricClass(row.usageRate)">{{ num(row.usageRate).toFixed(1) }}%</span>
          </template>
        </el-table-column>
        <el-table-column label="队列大小" width="100" align="right" prop="queueSize" />
        <el-table-column label="拒绝/分钟" width="110" align="right">
          <template #default="{ row }">{{ num(row.rejectPerMin).toFixed(1) }}</template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!loading && threadPoolData.length === 0" description="暂无线程池数据" />
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { exportCSV } from '@/utils/export-csv.js'
import {
  getRedisInstances, getMysqlInstances, getRocketmqInstances, getKafkaInstances,
  getLindormInstances, getElasticsearchInstances, getOssBuckets,
  getPodCpuTop, getPodMemoryTop, getNodeOverview,
  getRocketmqTopTopics, getKafkaTopPartitions,
  getRedisBigKeys, getRedisSlowQueries,
  getMysqlSlowQueries,
  getLindormTopTables, getElasticsearchTopIndices,
  getDbInstances, getDruidInstances, getJvmInstances, getThreadPoolInstances,
  checkMiddlewareAlerts
} from '@/api/middleware.js'

const loading = ref(false)
const activeTab = ref('redis')
const redisSubTab = ref('bigKeys')
const mysqlSubTab = ref('slowQueries')

const redisData = ref([])
const mysqlData = ref([])
const rocketmqData = ref([])
const kafkaData = ref([])
const lindormData = ref([])
const esData = ref([])
const ossData = ref([])
const podCpuData = ref([])
const podMemData = ref([])
const nodeData = ref([])

const dbData = ref([])
const druidData = ref([])
const jvmData = ref([])
const threadPoolData = ref([])

const rocketmqTopTopicsData = ref([])
const kafkaTopPartitionsData = ref([])
const redisBigKeysData = ref([])
const redisSlowQueriesData = ref([])
const mysqlSlowQueriesData = ref([])
const lindormTopTablesData = ref([])
const esTopIndicesData = ref([])

const alertSummary = ref({ total: 0, redCount: 0, yellowCount: 0, normalCount: 0 })

const metricClass = (val) => {
  if (val > 80) return 'metric-critical'
  if (val > 50) return 'metric-warning'
  return 'metric-normal'
}

const alertMetricClass = (row, metric) => {
  if (row.alertLevel === 'red') {
    const details = row.alertDetails || []
    if (details.some(d => d.metricName === metric && d.level === 'red')) return 'metric-critical'
  }
  if (row.alertLevel === 'yellow' || row.alertLevel === 'red') {
    const details = row.alertDetails || []
    if (details.some(d => d.metricName === metric)) return 'metric-warning'
  }
  return 'metric-normal'
}

const formatBytes = (bytes) => {
  if (!bytes || bytes === 0) return '0 B'
  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  const i = Math.floor(Math.log(bytes) / Math.log(1024))
  return (bytes / Math.pow(1024, i)).toFixed(1) + ' ' + units[i]
}

const formatTime = (ts) => {
  if (!ts) return '-'
  const d = new Date(ts)
  return d.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit', second: '2-digit' })
}

const num = (val, fallback = 0) => {
  const n = Number(val)
  return Number.isFinite(n) ? n : fallback
}

const fetchAlerts = async (type, dataRef) => {
  try {
    const res = await checkMiddlewareAlerts(type)
    if (res.data) {
      alertSummary.value = res.data.summary || { total: 0, redCount: 0, yellowCount: 0, normalCount: 0 }
      if (res.data.instances && Array.isArray(res.data.instances)) {
        const alertMap = new Map(res.data.instances.map(i => [i.instanceId, i]))
        dataRef.value = dataRef.value.map(item => {
          const alert = alertMap.get(item.instanceId)
          if (alert) {
            return { ...item, alertLevel: alert.alertLevel, alertDetails: alert.alertDetails }
          }
          return item
        })
      }
    }
  } catch (e) {
    console.warn('Alert check failed:', e)
  }
}

const fetchData = async () => {
  loading.value = true
  try {
    if (activeTab.value === 'redis') {
      const res = await getRedisInstances()
      redisData.value = res.data || []
      await fetchAlerts('redis', redisData)
      const [keysRes, slowRes] = await Promise.all([getRedisBigKeys(), getRedisSlowQueries()])
      redisBigKeysData.value = keysRes.data || []
      redisSlowQueriesData.value = slowRes.data || []
    } else if (activeTab.value === 'mysql') {
      const res = await getMysqlInstances()
      mysqlData.value = res.data || []
      await fetchAlerts('mysql', mysqlData)
      const [slowRes, dbRes, druidRes] = await Promise.all([getMysqlSlowQueries(), getDbInstances(), getDruidInstances()])
      mysqlSlowQueriesData.value = slowRes.data || []
      dbData.value = dbRes.data || []
      druidData.value = druidRes.data || []
    } else if (activeTab.value === 'rocketmq') {
      const res = await getRocketmqInstances()
      rocketmqData.value = res.data || []
      await fetchAlerts('rocketmq', rocketmqData)
      const topRes = await getRocketmqTopTopics()
      rocketmqTopTopicsData.value = topRes.data || []
    } else if (activeTab.value === 'kafka') {
      const res = await getKafkaInstances()
      kafkaData.value = res.data || []
      await fetchAlerts('kafka', kafkaData)
      const topRes = await getKafkaTopPartitions()
      kafkaTopPartitionsData.value = topRes.data || []
    } else if (activeTab.value === 'lindorm') {
      const res = await getLindormInstances()
      lindormData.value = res.data || []
      await fetchAlerts('lindorm', lindormData)
      const topRes = await getLindormTopTables()
      lindormTopTablesData.value = topRes.data || []
    } else if (activeTab.value === 'elasticsearch') {
      const res = await getElasticsearchInstances()
      esData.value = res.data || []
      await fetchAlerts('elasticsearch', esData)
      const topRes = await getElasticsearchTopIndices()
      esTopIndicesData.value = topRes.data || []
    } else if (activeTab.value === 'oss') {
      const res = await getOssBuckets()
      ossData.value = res.data || []
      await fetchAlerts('oss', ossData)
    } else if (activeTab.value === 'pod') {
      const [cpuRes, memRes] = await Promise.all([getPodCpuTop(), getPodMemoryTop()])
      podCpuData.value = cpuRes.data || []
      podMemData.value = memRes.data || []
    } else if (activeTab.value === 'node') {
      const res = await getNodeOverview()
      nodeData.value = res.data || []
    } else if (activeTab.value === 'jvm') {
      const res = await getJvmInstances()
      jvmData.value = res.data || []
    } else if (activeTab.value === 'threadpool') {
      const res = await getThreadPoolInstances()
      threadPoolData.value = res.data || []
    }
  } catch (error) {
    console.error('Fetch error:', error)
  } finally {
    loading.value = false
  }
}

const handleExport = () => {
  let data = [], filename = '中间件监控', columns = []
  if (activeTab.value === 'redis') {
    data = redisData.value; filename = 'Redis监控'
    columns = [{label:'实例ID',prop:'instanceId'},{label:'实例名称',prop:'instanceName'},{label:'CPU%',prop:'cpuUsage'},{label:'连接数',prop:'connections'},{label:'内存%',prop:'memoryUsage'},{label:'QPS',prop:'qps'}]
  } else if (activeTab.value === 'mysql') {
    data = mysqlData.value; filename = 'MySQL监控'
    columns = [{label:'实例ID',prop:'instanceId'},{label:'实例名称',prop:'instanceName'},{label:'CPU%',prop:'cpuUsage'},{label:'连接数',prop:'connections'},{label:'IOPS',prop:'iops'},{label:'磁盘%',prop:'diskUsage'}]
  } else if (activeTab.value === 'node') {
    data = nodeData.value; filename = 'Node监控'
    columns = [{label:'节点',prop:'node'},{label:'CPU%',prop:'cpuUsage'},{label:'内存%',prop:'memoryUsage'}]
  } else if (activeTab.value === 'pod') {
    data = podCpuData.value; filename = 'PodCPU监控'
    columns = [{label:'Pod',prop:'pod'},{label:'CPU',prop:'cpu'}]
  } else if (activeTab.value === 'jvm') {
    data = jvmData.value; filename = 'JVM监控'
    columns = [{label:'应用',prop:'application'},{label:'堆内存%',prop:'heapUsage'},{label:'GC/s',prop:'gcRate'},{label:'QPS',prop:'qps'},{label:'CPU%',prop:'cpuUsage'}]
  } else if (activeTab.value === 'threadpool') {
    data = threadPoolData.value; filename = '线程池监控'
    columns = [{label:'应用',prop:'application'},{label:'线程池',prop:'threadPoolName'},{label:'活跃线程',prop:'activeCount'},{label:'最大线程',prop:'maxSize'},{label:'使用率%',prop:'usageRate'},{label:'队列大小',prop:'queueSize'},{label:'拒绝/分钟',prop:'rejectPerMin'}]
  }
  exportCSV(filename, data, columns)
}

onMounted(() => fetchData())
</script>

<style scoped>
.middleware-page { padding: 16px; }
.page-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px; }
.page-header h2 { margin: 0; font-size: 20px; }
.header-actions { display: flex; align-items: center; gap: 12px; }
.monitor-section { margin-bottom: 20px; }
.sub-section { margin-top: 16px; }
.sub-title { font-size: 14px; font-weight: 600; margin-bottom: 8px; color: #303133; }
.alert-summary-bar { display: flex; gap: 8px; margin-bottom: 16px; }
.pod-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; }
.pod-card { border: 1px solid #ebeef5; border-radius: 8px; padding: 12px; }
.card-title { font-size: 14px; font-weight: 600; margin-bottom: 8px; }
.metric-critical { color: #f56c6c; font-weight: 700; }
.metric-warning { color: #e6a23c; font-weight: 600; }
.metric-normal { color: #67c23a; }

.alert-dot-red {
  display: inline-block; width: 10px; height: 10px; border-radius: 50%;
  background: #f56c6c; box-shadow: 0 0 4px #f56c6c;
}
.alert-dot-yellow {
  display: inline-block; width: 10px; height: 10px; border-radius: 50%;
  background: #e6a23c; box-shadow: 0 0 4px #e6a23c;
}
.alert-dot-normal {
  display: inline-block; width: 10px; height: 10px; border-radius: 50%;
  background: #67c23a;
}
</style>
