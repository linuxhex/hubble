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

export function getPodCpuTop() {
  return request.get('/middleware/pod/cpu')
}

export function getPodMemoryTop() {
  return request.get('/middleware/pod/memory')
}

export function getNodeOverview() {
  return request.get('/middleware/node/overview')
}
