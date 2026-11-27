<script setup>
import { onMounted, ref } from 'vue'
import StaffWorkspace from './cases/StaffWorkspace.vue'

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
    <header class="app-header">
      <h1>ProofFlow Investigator</h1>
    </header>
    <StaffWorkspace />
    <details class="diagnostics">
      <summary>连接诊断</summary>
      <div class="health">
        <p role="status">{{ status }}</p>
        <button type="button" :disabled="pending" @click="checkHealth">重新检查</button>
      </div>
    </details>
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
  max-width: 65rem;
  margin: 2rem auto;
  padding: 0 1.5rem;
}

.app-header {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 0.75rem;
  padding-bottom: 1rem;
  border-bottom: 1px solid #dbe3ed;
}

h1 { margin: 0; font-size: 1.25rem; }
.health { display: flex; flex-wrap: wrap; align-items: center; gap: 0.65rem; }
.health p { margin: 0; color: #46566b; font-size: 0.875rem; }
.diagnostics { margin-top: 1.5rem; color: #46566b; font-size: .875rem; }

button {
  padding: 0.5rem 0.875rem;
  border: 1px solid #aab8c9;
  border-radius: 0.4rem;
  background: #fff;
  color: inherit;
  font: inherit;
  cursor: pointer;
}

button:disabled { cursor: default; opacity: 0.5; }
button:focus-visible, input:focus-visible { outline: 2px solid #315f9d; outline-offset: 2px; }
</style>
