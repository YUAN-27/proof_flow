<script setup>
import { onMounted, ref } from 'vue'

const status = ref('正在检查业务 API')
const pending = ref(false)

async function checkHealth() {
  pending.value = true
  status.value = '正在检查业务 API'

  try {
    const response = await fetch('/api/v1/health', {
      signal: AbortSignal.timeout(5000),
    })
    if (!response.ok) {
      throw new Error('业务 API 返回错误')
    }

    const health = await response.json()
    status.value = health.service === 'business-api' && health.status === 'ok'
      ? '业务 API 已连接'
      : '业务 API 返回异常'
  } catch {
    status.value = '业务 API 暂不可用'
  } finally {
    pending.value = false
  }
}

onMounted(checkHealth)
</script>

<template>
  <main>
    <h1>ProofFlow Investigator</h1>
    <p role="status">{{ status }}</p>
    <button type="button" :disabled="pending" @click="checkHealth">重新检查</button>
  </main>
</template>

<style>
body {
  margin: 0;
  color: #182637;
  background: #f4f7fa;
  font: 1rem/1.5 system-ui, sans-serif;
}

main {
  max-width: 44rem;
  margin: 4rem auto;
  padding: 0 1.5rem;
}

button {
  padding: 0.5rem 0.875rem;
  font: inherit;
}
</style>
