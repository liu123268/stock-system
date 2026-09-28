<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { changePassword } from '@/api/auth'
import { useAuthStore } from '@/stores/auth'
import { useWorkspaceStore } from '@/stores/workspace'

const router = useRouter()
const authStore = useAuthStore()
const workspaceStore = useWorkspaceStore()
const nickname = ref(authStore.currentUser?.nickname ?? '')
const nicknameSaving = ref(false)
const nicknameMessage = ref('')
const nicknameError = ref('')
const currentPassword = ref('')
const newPassword = ref('')
const confirmPassword = ref('')
const passwordSaving = ref(false)
const passwordError = ref('')
const returnPath = authStore.isSuperAdmin ? '/admin/users' : '/workspaces'

async function saveNickname() {
  if (nicknameSaving.value) return
  nicknameMessage.value = ''
  nicknameError.value = ''
  const value = nickname.value.trim()
  if (!value || value.length > 20) {
    nicknameError.value = '昵称需要 1 到 20 个字符'
    return
  }
  nicknameSaving.value = true
  try {
    const user = await authStore.updateNickname(value)
    nickname.value = user.nickname
    nicknameMessage.value = '昵称已更新。'
  } catch (error) {
    nicknameError.value = error instanceof Error ? error.message : '昵称修改失败'
  } finally {
    nicknameSaving.value = false
  }
}

async function savePassword() {
  if (passwordSaving.value) return
  passwordError.value = ''
  if (newPassword.value.length < 4 || newPassword.value.length > 64) {
    passwordError.value = '新密码长度需要 4 到 64 个字符'
    return
  }
  if (newPassword.value !== confirmPassword.value) {
    passwordError.value = '两次输入的新密码不一致'
    return
  }
  passwordSaving.value = true
  try {
    await changePassword(currentPassword.value, newPassword.value)
    currentPassword.value = ''
    newPassword.value = ''
    confirmPassword.value = ''
    authStore.logout()
    workspaceStore.clearAll()
    await router.replace({ name: 'login', query: { reason: 'password-changed' } })
  } catch (error) {
    passwordError.value = error instanceof Error ? error.message : '密码修改失败'
  } finally {
    passwordSaving.value = false
  }
}
</script>

<template>
  <main class="profile-page">
    <header>
      <RouterLink :to="returnPath">← 返回{{ authStore.isSuperAdmin ? '治理后台' : '空间选择' }}</RouterLink>
      <h1>个人资料</h1>
      <p>这里管理你的账号资料，与你当前进入哪个空间无关。</p>
    </header>

    <section aria-labelledby="nickname-title">
      <h2 id="nickname-title">展示昵称</h2>
      <p>登录账号 {{ authStore.currentUser?.username }} 不会随昵称改变。</p>
      <form @submit.prevent="saveNickname">
        <label for="profile-nickname">昵称</label>
        <input id="profile-nickname" v-model="nickname" maxlength="20" autocomplete="nickname" />
        <button type="submit" :disabled="nicknameSaving">{{ nicknameSaving ? '正在保存…' : '保存昵称' }}</button>
      </form>
      <p v-if="nicknameError" class="error" role="alert">{{ nicknameError }}</p>
      <p v-if="nicknameMessage" class="success" aria-live="polite">{{ nicknameMessage }}</p>
    </section>

    <section aria-labelledby="password-title">
      <h2 id="password-title">修改密码</h2>
      <p>保存成功后，当前和其他设备都需要使用新密码重新登录。</p>
      <form @submit.prevent="savePassword">
        <label for="current-password">原密码</label>
        <input id="current-password" v-model="currentPassword" type="password" autocomplete="current-password" required />
        <label for="new-password">新密码</label>
        <input id="new-password" v-model="newPassword" type="password" autocomplete="new-password" minlength="4" maxlength="64" required />
        <label for="confirm-password">确认新密码</label>
        <input id="confirm-password" v-model="confirmPassword" type="password" autocomplete="new-password" required />
        <button type="submit" :disabled="passwordSaving">{{ passwordSaving ? '正在保存…' : '修改密码并重新登录' }}</button>
      </form>
      <p v-if="passwordError" class="error" role="alert">{{ passwordError }}</p>
    </section>
  </main>
</template>

<style scoped>
.profile-page { max-width: 760px; margin: 0 auto; padding: 48px 24px 80px; color: var(--color-ink); }
header > a { color: var(--color-focus); }
h1 { margin-top: 28px; font-size: clamp(32px, 5vw, 48px); }
section { margin-top: 42px; padding-top: 28px; border-top: 1px solid var(--color-border); }
h2 { font-size: 22px; }
p { margin-top: 10px; color: var(--color-muted); line-height: 1.6; }
form { display: grid; gap: 10px; max-width: 420px; margin-top: 24px; }
label { font-size: 13px; font-weight: 650; }
input { min-height: 44px; padding: 0 12px; border: 1px solid var(--color-border); border-radius: var(--radius-control); background: var(--color-surface); color: var(--color-ink); }
button { min-height: 44px; margin-top: 12px; border: 0; border-radius: var(--radius-action); background: var(--color-ink); color: white; cursor: pointer; }
button:disabled { opacity: 0.55; cursor: wait; }
.error { color: var(--color-danger); }
.success { color: var(--color-focus); }
</style>
