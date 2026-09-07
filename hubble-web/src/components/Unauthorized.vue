<template>
  <div class="unauthorized-container">
    <el-result
      icon="warning"
      title="未授权访问"
      sub-title="您尚未登录或登录已过期，请返回父系统重新登录"
    >
      <template #extra>
        <el-button type="primary" @click="goToParentSystem">返回父系统</el-button>
      </template>
    </el-result>
  </div>
</template>

<script setup>
const goToParentSystem = () => {
  const parentSystemUrl = import.meta.env.VITE_PARENT_SYSTEM_URL || 'http://parent-system.example.com'
  window.location.href = parentSystemUrl
}
</script>

<style scoped>
.unauthorized-container {
  display: flex;
  justify-content: center;
  align-items: center;
  min-height: 400px;
  background-color: #f0f2f5;
}
</style>
