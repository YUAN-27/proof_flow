<script setup>
import { ref, watch } from 'vue'
import { readCaseAudit } from './caseApi.js'

const props = defineProps({
  caseId: { type: String, required: true },
  credential: { type: [String, Object], required: true },
  states: { type: Object, required: true },
  version: { type: Number, required: true },
})
const entries = ref(null)
const error = ref(false)
const retry = ref(0)
const formatter = new Intl.DateTimeFormat('zh-CN', { dateStyle: 'short', timeStyle: 'short' })

watch([() => props.caseId, () => props.version, () => props.credential, retry], async (_, __, onCleanup) => {
  const request = new AbortController()
  onCleanup(() => request.abort())
  error.value = false
  entries.value = null
  try {
    const history = await readCaseAudit(props.credential, props.caseId, request.signal)
    if (!request.signal.aborted) entries.value = history
  } catch {
    if (!request.signal.aborted) error.value = true
  }
}, { immediate: true })

function action(entry) {
  const label = props.states[entry.status]?.label ?? entry.status
  if (entry.operation === 'CREATED') return '创建工单'
  return `${props.states[entry.previousStatus]?.label ?? entry.previousStatus} → ${label}`
}
</script>

<template>
  <section class="timeline" aria-labelledby="timeline-title">
    <h4 id="timeline-title">处理历史</h4>
    <div v-if="error">
      <p role="alert">处理历史暂不可用。</p>
      <button type="button" @click="retry++">重试</button>
    </div>
    <p v-else-if="!entries" role="status">正在加载处理历史…</p>
    <p v-else-if="!entries.length">暂无处理记录。</p>
    <ol v-else>
      <li v-for="entry in entries" :key="entry.version">
        <strong>{{ action(entry) }}</strong>
        <span>{{ entry.actorId }} · <time :datetime="entry.occurredAt">{{ formatter.format(new Date(entry.occurredAt)) }}</time></span>
      </li>
    </ol>
  </section>
</template>

<style scoped>
.timeline { margin-top: 1.35rem; padding-top: 1rem; border-top: 1px solid #dbe3ed; }
h4 { margin: 0; font-size: 1rem; }
p, span { color: #46566b; font-size: .875rem; }
ol { list-style: none; margin: .75rem 0 0; padding-left: .8rem; border-left: 2px solid #bfd4ed; }
li { padding-bottom: .6rem; overflow-wrap: anywhere; }
span { display: block; }
</style>
