<script setup>
import { ref, watch } from 'vue'
import { listCases } from './caseApi.js'

const props = defineProps({
  credential: { type: [String, Object], required: true },
  states: { type: Object, required: true },
  selectedId: { type: String, default: null },
  revision: { type: Number, required: true },
  disabled: Boolean,
})
defineEmits(['select'])

const pageSize = 25
const text = ref('')
const query = ref('')
const status = ref('OPEN')
const offset = ref(0)
const items = ref([])
const hasMore = ref(false)
const pending = ref(false)
const error = ref(false)
const refresh = ref(0)

function search() {
  offset.value = 0
  query.value = text.value.trim()
  refresh.value++
}

watch([() => props.credential, status, query, offset, () => props.revision, refresh], async (_, __, onCleanup) => {
  const request = new AbortController()
  onCleanup(() => request.abort())
  items.value = []
  hasMore.value = false
  error.value = false
  pending.value = true
  try {
    const page = await listCases(props.credential, {
      status: status.value, query: query.value, offset: offset.value, signal: request.signal,
    })
    if (request.signal.aborted) return
    if (!page.items.length && offset.value) {
      offset.value = Math.max(0, offset.value - pageSize)
      return
    }
    items.value = page.items
    hasMore.value = page.hasMore
  } catch {
    if (!request.signal.aborted) error.value = true
  } finally {
    if (!request.signal.aborted) pending.value = false
  }
}, { immediate: true })
</script>

<template>
  <section aria-labelledby="queue-title">
    <div class="queue-heading">
      <h3 id="queue-title">工单队列</h3>
      <button type="button" :disabled="pending" @click="refresh++">刷新</button>
    </div>
    <form class="filters" @submit.prevent="search">
      <label for="queue-search">标题或工单 ID</label>
      <div class="search">
        <input id="queue-search" v-model="text" type="search" maxlength="200" spellcheck="false">
        <button type="submit">搜索</button>
      </div>
      <label for="queue-status">状态</label>
      <select id="queue-status" v-model="status" @change="offset = 0">
        <option value="">全部状态</option>
        <option v-for="(state, key) in states" :key="key" :value="key">{{ state.label }}</option>
      </select>
    </form>
    <p v-if="pending" role="status">正在加载工单…</p>
    <p v-else-if="error" role="alert">加载失败，请刷新重试。</p>
    <p v-else-if="!items.length" role="status">没有符合条件的工单。</p>
    <ul v-else>
      <li v-for="item in items" :key="item.id">
        <button type="button" :disabled="disabled" :aria-current="selectedId === item.id ? 'true' : undefined"
          @click="$emit('select', item.id)">
          <strong>{{ item.title }}</strong>
          <span><code>{{ item.id.slice(0, 8) }}</code>{{ states[item.status]?.label }}</span>
        </button>
      </li>
    </ul>
    <nav v-if="offset || hasMore" class="pages" aria-label="工单分页">
      <button type="button" :disabled="!offset || pending" @click="offset -= pageSize">上一页</button>
      <span>第 {{ offset / pageSize + 1 }} 页</span>
      <button type="button" :disabled="!hasMore || pending" @click="offset += pageSize">下一页</button>
    </nav>
  </section>
</template>

<style scoped>
.queue-heading, .pages { display: flex; align-items: center; justify-content: space-between; gap: .45rem; }
h3 { margin: 0; }
.filters { display: grid; gap: .4rem; margin: .8rem 0; }
label, p, .pages { color: #46566b; font-size: .875rem; }
.search { display: grid; grid-template-columns: minmax(0, 1fr) auto; gap: .4rem; }
input, select { width: 100%; box-sizing: border-box; border: 1px solid #aab8c9; border-radius: .4rem; padding: .45rem; font: inherit; }
ul { list-style: none; padding: 0; margin: .8rem 0; display: grid; gap: .4rem; }
li { min-width: 0; }
li button { width: 100%; text-align: left; overflow-wrap: anywhere; }
li button:hover, li button[aria-current] { background: #eaf2ff; border-color: #315f9d; }
li span { display: flex; flex-wrap: wrap; justify-content: space-between; gap: .25rem; font-size: .8rem; color: #46566b; }
</style>
