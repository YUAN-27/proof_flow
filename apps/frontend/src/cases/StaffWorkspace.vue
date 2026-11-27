<script setup>
import { onMounted, ref } from 'vue'
import CaseWorkspace from './CaseWorkspace.vue'
import { currentStaff, loginStaff, logoutStaff } from './staffApi.js'

const mode = ref('checking')
const staff = ref(null)
const username = ref('')
const password = ref('')
const busy = ref(false)
const notice = ref('')

function sessionExpired() {
  staff.value = null
  notice.value = '会话已过期，请重新登录。'
}

const credential = { expired: sessionExpired }

async function initialize() {
  mode.value = 'checking'
  try {
    staff.value = await currentStaff()
    mode.value = 'staff'
  } catch (error) {
    if (error.status === 401) mode.value = 'staff'
    else if (error.status === 404) mode.value = 'legacy'
    else mode.value = 'unavailable'
  }
}

async function login() {
  if (busy.value) return
  busy.value = true
  notice.value = ''
  try {
    await loginStaff(username.value.trim(), password.value)
    staff.value = await currentStaff()
  } catch (error) {
    notice.value = error.status === 401 ? '登录失败，请核对客服账号和密码。' : '登录服务暂不可用，请重试。'
  } finally {
    password.value = ''
    busy.value = false
  }
}

async function logout() {
  if (busy.value) return
  busy.value = true
  notice.value = ''
  try {
    await logoutStaff()
    staff.value = null
  } catch {
    notice.value = '退出未确认，请重试。'
  } finally {
    busy.value = false
  }
}

onMounted(initialize)
</script>

<template>
  <CaseWorkspace v-if="mode === 'legacy'" />
  <template v-else-if="mode === 'staff'">
    <div v-if="staff" class="staff-bar">
      <span>客服 {{ staff.actorId }}</span>
      <button type="button" :disabled="busy" @click="logout">退出登录</button>
    </div>
    <form v-else class="staff-login" @submit.prevent="login">
      <h2>客服登录</h2>
      <label for="staff-username">账号</label>
      <input id="staff-username" v-model="username" autocomplete="username" maxlength="64" required :disabled="busy">
      <label for="staff-password">密码</label>
      <input id="staff-password" v-model="password" type="password" autocomplete="current-password" required :disabled="busy">
      <button type="submit" :disabled="busy">{{ busy ? '正在登录' : '登录' }}</button>
    </form>
    <p v-if="notice" role="alert">{{ notice }}</p>
    <CaseWorkspace v-if="staff" :staff-credential="credential" />
  </template>
  <p v-else-if="mode === 'checking'" role="status">正在检查登录状态。</p>
  <div v-else>
    <p role="alert">无法检查登录状态。</p>
    <button type="button" @click="initialize">重试</button>
  </div>
</template>

<style scoped>
.staff-bar { display: flex; justify-content: flex-end; align-items: center; gap: 1rem; margin-top: 1rem; }
.staff-login {
  display: grid;
  gap: 0.65rem;
  max-width: 23rem;
  margin: 2rem auto;
  padding: 1.5rem;
  border: 1px solid #dbe3ed;
  border-radius: 0.65rem;
  background: #fff;
}
.staff-login h2 { margin: 0 0 0.5rem; }
.staff-login input { min-width: 0; padding: 0.55rem; border: 1px solid #aab8c9; border-radius: 0.4rem; font: inherit; }
.staff-login button { margin-top: 0.5rem; background: #244c7d; color: #fff; }
</style>
