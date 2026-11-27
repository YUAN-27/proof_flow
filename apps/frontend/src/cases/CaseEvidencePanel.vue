<script setup>
import { onMounted, onUnmounted, ref } from 'vue'
import { downloadEvidence, listEvidence, uploadEvidence } from './caseApi.js'

const props = defineProps({
  caseId: { type: String, required: true },
  token: { type: String, required: true },
})

const maxFileBytes = 10 * 1024 * 1024
const requests = new AbortController()
const entries = ref(null)
const file = ref(null)
const fileInput = ref(null)
const busy = ref(false)
const notice = ref(null)

const errors = {
  400: '文件为空或文件名无效。',
  401: '凭据已失效，请重新填写联调凭据。',
  404: '工单或附件不存在，或当前服务尚未启用附件 API。',
  413: '单个文件不能超过 10 MiB。',
  415: '请选择 PDF、PNG 或 JPEG 文件。',
  503: '当前附件存储空间已满。',
}

async function run(operation) {
  if (busy.value) return
  busy.value = true
  notice.value = null
  try {
    await operation(requests.signal)
  } catch (error) {
    if (!requests.signal.aborted) {
      notice.value = { text: errors[error.status] || '附件请求失败，请检查连接后重试。', error: true }
      if (error.status === 401) entries.value = null
    }
  } finally {
    busy.value = false
  }
}

function refresh() {
  return run(async (signal) => {
    const records = await listEvidence(props.token, props.caseId, signal)
    if (!signal.aborted) entries.value = records
  })
}

function selectFile(event) {
  file.value = event.target.files[0] || null
  notice.value = null
}

function upload() {
  if (busy.value || entries.value === null || !file.value) return
  if (file.value.size < 1 || file.value.size > maxFileBytes) {
    notice.value = { text: '请选择大于 0 字节且不超过 10 MiB 的文件。', error: true }
    return
  }
  return run(async (signal) => {
    const created = await uploadEvidence(props.token, props.caseId, file.value, signal)
    if (signal.aborted) return
    entries.value = [...entries.value, created]
    file.value = null
    fileInput.value.value = ''
    notice.value = { text: '附件已上传。' }
  })
}

function download(entry) {
  return run(async (signal) => {
    const blob = await downloadEvidence(props.token, props.caseId, entry.id, signal)
    if (signal.aborted) return
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = entry.filename
    document.body.append(link)
    link.click()
    link.remove()
    setTimeout(() => URL.revokeObjectURL(url), 30000)
  })
}

function sizeLabel(bytes) {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KiB`
  return `${(bytes / (1024 * 1024)).toFixed(1)} MiB`
}

onMounted(refresh)
onUnmounted(() => requests.abort())
</script>

<template>
  <section class="attachments" aria-labelledby="case-evidence-title" :aria-busy="busy">
    <div class="attachments-heading">
      <h4 id="case-evidence-title">证据附件</h4>
      <button type="button" :disabled="busy" @click="refresh">刷新附件</button>
    </div>
    <p v-if="notice" :class="{ failure: notice.error }" :role="notice.error ? 'alert' : 'status'">
      {{ notice.text }}
    </p>
    <form v-if="entries !== null" class="upload-form" @submit.prevent="upload">
      <label for="case-evidence-file">选择证据文件</label>
      <input
        id="case-evidence-file"
        ref="fileInput"
        type="file"
        accept=".pdf,.png,.jpg,.jpeg,application/pdf,image/png,image/jpeg"
        aria-describedby="case-evidence-help"
        :disabled="busy"
        @change="selectFile"
      >
      <p id="case-evidence-help" class="muted">PDF、PNG 或 JPEG，单个文件不超过 10 MiB。</p>
      <button type="submit" :disabled="busy || !file">上传附件</button>
    </form>
    <p v-if="entries === null && busy" class="muted" role="status">正在加载附件…</p>
    <p v-else-if="entries?.length === 0" class="muted">暂无附件。</p>
    <ul v-else-if="entries?.length" class="attachment-list">
      <li v-for="entry in entries" :key="entry.id">
        <div class="file-details">
          <span>{{ entry.filename }}</span>
          <small class="muted">{{ sizeLabel(entry.sizeBytes) }} · {{ entry.uploadedBy }}</small>
        </div>
        <button type="button" :aria-label="`下载 ${entry.filename}`" :disabled="busy" @click="download(entry)">
          下载
        </button>
      </li>
    </ul>
  </section>
</template>

<style scoped>
.attachments { display: grid; gap: 0.85rem; margin-top: 1.25rem; padding-top: 1rem; border-top: 1px solid #dbe3ed; }
.attachments-heading, .attachment-list li { display: flex; align-items: center; justify-content: space-between; gap: 0.75rem; }
h4, p { margin: 0; }
h4 { font-size: 1rem; }
.upload-form { display: grid; gap: 0.45rem; }
.upload-form label { font-size: 0.875rem; font-weight: 600; }
.upload-form input { min-width: 0; width: 100%; font: inherit; }
.upload-form button { justify-self: start; }
.attachment-list { list-style: none; padding: 0; margin: 0; }
.attachment-list li { padding: 0.65rem 0; border-bottom: 1px solid #dbe3ed; }
.file-details { min-width: 0; display: grid; gap: 0.2rem; overflow-wrap: anywhere; }
.attachment-list button { flex-shrink: 0; }
.muted { color: #536278; font-size: 0.875rem; }
.failure { color: #852c23; }
</style>
