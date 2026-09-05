import request from '@/utils/request.js'

export function getRedisOverview() {
  return request.get('/middleware/redis')
}

export function getRedisInstances() {
  return request.get('/middleware/redis/instances')
}

export function getMysqlOverview() {
  return request.get('/middleware/mysql')
}

export function getMysqlInstances() {
  return request.get('/middleware/mysql/instances')
}

export function getRocketmqInstances() {
  return request.get('/middleware/rocketmq/instances')
}

export function getKafkaInstances() {
  return request.get('/middleware/kafka/instances')
}

export function getLindormInstances() {
  return request.get('/middleware/lindorm/instances')
}

export function getElasticsearchInstances() {
  return request.get('/middleware/elasticsearch/instances')
}

export function getOssBuckets() {
  return request.get('/middleware/oss/buckets')
}

export function getPodCpuTop() {
  return request.get('/middleware/pod/cpu')
}

export function getPodMemoryTop() {
  return request.get('/middleware/pod/memory')
}

export function getNodeOverview() {
  return request.get('/middleware/node/overview')
}

export function getRocketmqTopTopics() {
  return request.get('/middleware/rocketmq/top-topics')
}

export function getKafkaTopPartitions() {
  return request.get('/middleware/kafka/top-partitions')
}

export function getRedisBigKeys() {
  return request.get('/middleware/redis/big-keys')
}

export function getRedisSlowQueries() {
  return request.get('/middleware/redis/slow-queries')
}

export function getMysqlTopTables() {
  return request.get('/middleware/mysql/top-tables')
}

export function getMysqlSlowQueries() {
  return request.get('/middleware/mysql/slow-queries')
}

export function checkMiddlewareAlerts(type) {
  return request.get(`/middleware-alert/check/${type}`)
}

export function checkAllAlerts() {
  return request.get('/middleware-alert/check/all')
}

export function getAlertConfigs(middlewareType) {
  return request.get('/middleware-alert/config/list', { params: { middlewareType } })
}

export function createMiddlewareAlertConfig(config) {
  return request.post('/middleware-alert/config', config)
}

export function updateMiddlewareAlertConfig(id, config) {
  return request.put(`/middleware-alert/config/${id}`, config)
}

export function deleteMiddlewareAlertConfig(id) {
  return request.delete(`/middleware-alert/config/${id}`)
}

export function getLindormTopTables() {
  return request.get('/middleware/lindorm/top-tables')
}

export function getElasticsearchTopIndices() {
  return request.get('/middleware/elasticsearch/top-indices')
}
