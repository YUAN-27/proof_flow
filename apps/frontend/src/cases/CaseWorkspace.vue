<script setup>
import { computed, ref } from 'vue'
import { createCase, readCase, updateCaseStatus } from './caseApi.js'

const states = {
  OPEN: { label: '待审核', next: 'IN_REVIEW', action: '开始审核' },
  IN_REVIEW: { label: '审核中', next: 'CLOSED', action: '关闭工单' },
  CLOSED: { label: '已关闭' },
}

const token = ref('')
const title = ref('')
const lookupId = ref('')
const selected = ref(null)
const pending = ref(false)
const notice = ref(null)
const requiresRefresh = ref(false)
const ready = computed(() => token.value.trim() !== '' && !pending.value)
const state = computed(() => selected.value ? states[selected.value.status] : null)

function resetSelection() {
  selected.value = null
  requiresRefresh.value = false
  notice.value = null
}

function displayCase(record) {
  selected.value = record
  lookupId.value = record.id
  requiresRefresh.value = false
}

async function run(operation) {
  if (!ready.value) return
  pending.value = true
  notice.value = null
  try {
    await operation(token.value.trim())
  } catch (error) {
    let message = '未收到有效的服务响应，请检查连接后再试。'
    if (error.status === 401) {
      resetSelection()
      message = '凭据无效或工单 API 尚未启用，请检查联调配置。'
    } else if (error.status === 404) {
      resetSelection()
      message = '没有找到此工单，请核对工单 ID。'
    } else if (error.status === 409) {
      requiresRefresh.value = true
      message = error.code === 'VERSION_CONFLICT'
        ? '工单已有更新，请刷新工单后再操作。'
        : '当前状态无法执行此操作，请刷新工单。'
    } else if (error.status === 400) {
      message = '输入无效，请检查工单标题或 ID。'
    }
    notice.value = { text: message, error: true }
  } finally {
    pending.value = false
  }
}

function submitNewCase() {
  if (!title.value.trim()) return
  return run(async (credential) => {
    displayCase(await createCase(credential, title.value.trim()))
    title.value = ''
    notice.value = { text: '工单已创建。' }
  })
}

function loadCase(id) {
  const targetId = id.trim()
  if (!targetId) return
  return run(async (credential) => {
    if (selected.value?.id !== targetId) resetSelection()
    displayCase(await readCase(credential, targetId))
  })
}

function advanceCase() {
  if (!selected.value || requiresRefresh.value || !state.value?.next) return
  const { id, version } = selected.value
  const next = state.value.next
  return run(async (credential) => {
    displayCase(await updateCaseStatus(credential, id, version, next))
    notice.value = { text: '状态已更新。' }
  })
}
</script>

<template>
  <section class="workspace" aria-labelledby="workbench-title">
    <div class="workspace-heading">
      <h2 id="workbench-title">工单工作台</h2>
      <div class="credential">
        <label for="case-token">本地联调凭据</label>
        <input
          id="case-token"
          v-model="token"
          type="password"
          autocomplete="off"
          spellcheck="false"
          aria-describedby="case-token-help"
          :disabled="pending"
          @input="resetSelection"
        >
        <p id="case-token-help" class="muted">输入服务端配置的 Bearer token。</p>
      </div>
    </div>

    <p v-if="notice" class="notice" :class="{ error: notice.error }" :role="notice.error ? 'alert' : 'status'">
      {{ notice.text }}
    </p>

    <div class="workspace-grid">
      <div class="actions">
        <form class="panel" @submit.prevent="submitNewCase">
          <h3>创建工单</h3>
          <label for="new-case-title">工单标题</label>
          <input id="new-case-title" v-model="title" type="text" required :disabled="pending">
          <button class="primary" type="submit" :disabled="!ready || !title.trim()">创建工单</button>
        </form>
        <form class="panel" @submit.prevent="loadCase(lookupId)">
          <h3>查询工单</h3>
          <label for="lookup-case-id">工单 ID</label>
          <input id="lookup-case-id" v-model="lookupId" type="text" required spellcheck="false" :disabled="pending">
          <button type="submit" :disabled="!ready || !lookupId.trim()">查询工单</button>
        </form>
      </div>

      <section class="panel detail" aria-labelledby="case-detail-title" :aria-busy="pending">
        <h3 id="case-detail-title">工单详情</h3>
        <template v-if="selected">
          <h4>{{ selected.title }}</h4>
          <ol aria-label="工单进度" class="steps">
            <li v-for="(item, key) in states" :key="key" :aria-current="selected.status === key ? 'step' : undefined">
              {{ item.label }}
            </li>
          </ol>
          <dl>
            <dt>工单 ID</dt><dd class="case-id">{{ selected.id }}</dd>
            <dt>当前状态</dt><dd>{{ state?.label || selected.status }}</dd>
            <dt>版本</dt><dd>{{ selected.version }}</dd>
          </dl>
          <div class="detail-actions">
            <button v-if="state?.action" class="primary" type="button" :disabled="!ready || requiresRefresh" @click="advanceCase">
              {{ state.action }}
            </button>
            <button type="button" :disabled="!ready" @click="loadCase(selected.id)">刷新工单</button>
          </div>
        </template>
        <p v-else class="muted">创建一张工单，或输入已有工单 ID 查看详情。</p>
      </section>
    </div>
  </section>
</template>

<style scoped>
.workspace-heading {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 1.25rem;
  margin: 2rem 0 1.25rem;
}

h2, h3, h4, p { margin: 0; }
h2 { font-size: clamp(1.6rem, 4vw, 2.2rem); }
h3 { font-size: 1rem; }
h4 { margin-top: 1.25rem; font-size: 1.35rem; overflow-wrap: anywhere; }
.muted { color: #536278; font-size: 0.875rem; }
.credential { width: min(100%, 20rem); }
.credential p { margin-top: 0.35rem; }

.workspace-grid { display: grid; grid-template-columns: 1fr 1.45fr; gap: 1rem; }
.actions { display: grid; gap: 1rem; align-content: start; }
.panel { min-width: 0; padding: 1.25rem; border: 1px solid #dbe3ed; border-radius: 0.75rem; background: #fff; }
.panel > .muted { margin-top: 1rem; }
form { display: grid; gap: 0.6rem; }
form h3 { margin-bottom: 0.35rem; }
form button { justify-self: start; margin-top: 0.35rem; }
label { display: block; font-size: 0.875rem; font-weight: 600; }
input { box-sizing: border-box; width: 100%; padding: 0.6rem 0.7rem; border: 1px solid #aab8c9; border-radius: 0.4rem; font: inherit; }
.primary { color: #fff; background: #174782; border-color: #174782; }
.primary:not(:disabled):hover { background: #103764; }
.notice { margin-bottom: 1rem; padding: 0.65rem 0.85rem; border-radius: 0.4rem; background: #e6f1e9; color: #234c34; }
.notice.error { background: #fce9e6; color: #852c23; }

.steps { display: flex; flex-wrap: wrap; gap: 0.4rem; margin: 1rem 0; padding: 0; list-style: none; }
.steps li { padding: 0.25rem 0.55rem; border-radius: 0.3rem; background: #edf1f6; color: #46566b; font-size: 0.85rem; }
.steps [aria-current] { background: #dceafd; color: #173e70; font-weight: 700; }
dl { display: grid; grid-template-columns: max-content 1fr; gap: 0.65rem 0.9rem; margin: 1.25rem 0; }
dt { color: #536278; }
dd { margin: 0; overflow-wrap: anywhere; }
.case-id { font: 0.875rem/1.6 ui-monospace, monospace; }
.detail-actions { display: flex; flex-wrap: wrap; gap: 0.55rem; }

@media (max-width: 45rem) {
  .workspace-heading { flex-direction: column; }
  .workspace-grid { grid-template-columns: 1fr; }
}
</style>
