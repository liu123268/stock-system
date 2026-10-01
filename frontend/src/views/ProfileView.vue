<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from 'vue'
import { useRouter } from 'vue-router'
import { changePassword } from '@/api/auth'
import { useAuthStore } from '@/stores/auth'
import { useWorkspaceStore } from '@/stores/workspace'
import { isAuthenticationFailure } from '@/utils/authentication-error'

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
const showPasswords = ref(false)
const busy = computed(() => nicknameSaving.value || passwordSaving.value)
const returnPath = computed(() => (authStore.isSuperAdmin ? '/admin/users' : '/workspaces'))
let disposed = false
const isCurrent = (session: number) => !disposed && session === authStore.sessionVersion

async function handleAccessError(error: unknown, session: number) {
  if (session !== authStore.sessionVersion || !isAuthenticationFailure(error)) return false
  authStore.logout()
  workspaceStore.clearAll()
  await router.replace({ name: 'login', query: { redirect: '/profile', reason: 'expired' } })
  return true
}

async function saveNickname() {
  if (busy.value) return
  nicknameMessage.value = ''
  nicknameError.value = ''
  const value = nickname.value.trim()
  if (!value || value.length > 20) {
    nicknameError.value = '昵称需要 1 到 20 个字符'
    return
  }
  const session = authStore.sessionVersion
  nicknameSaving.value = true
  try {
    const user = await authStore.updateNickname(value)
    if (!isCurrent(session) || !user) return
    nickname.value = user.nickname
    nicknameMessage.value = '昵称已更新。'
  } catch (error) {
    if (!isCurrent(session)) return
    if (await handleAccessError(error, session)) return
    nicknameError.value = error instanceof Error ? error.message : '昵称修改失败'
  } finally {
    if (isCurrent(session)) nicknameSaving.value = false
  }
}

async function savePassword() {
  if (busy.value) return
  passwordError.value = ''
  if (newPassword.value.length < 4 || newPassword.value.length > 64) {
    passwordError.value = '新密码长度需要 4 到 64 个字符'
    return
  }
  if (newPassword.value !== confirmPassword.value) {
    passwordError.value = '两次输入的新密码不一致'
    return
  }
  const session = authStore.sessionVersion
  const username = authStore.currentUser?.username
  passwordSaving.value = true
  try {
    await changePassword(currentPassword.value, newPassword.value)
    // 改密成功是会话边界，即使已离开资料页也作废本次旧会话，但不触碰新账号。
    if (session !== authStore.sessionVersion) return
    currentPassword.value = ''
    newPassword.value = ''
    confirmPassword.value = ''
    authStore.logout()
    workspaceStore.clearAll()
    await router.replace({ name: 'login', query: { reason: 'password-changed', username } })
  } catch (error) {
    if (!isCurrent(session)) return
    if (await handleAccessError(error, session)) return
    passwordError.value = error instanceof Error ? error.message : '密码修改失败'
  } finally {
    if (isCurrent(session)) passwordSaving.value = false
  }
}
onBeforeUnmount(() => {
  disposed = true
})
</script>
<template>
  <div class="profile-page research-theme">
    <a class="ui-skip-link" href="#profile-content">跳到个人资料</a>
    <header class="profile-header">
      <RouterLink class="ui-brand" to="/" aria-label="基点首页"
        >基点<span aria-hidden="true">。</span></RouterLink
      >
      <RouterLink :to="returnPath"
        >← 返回{{ authStore.isSuperAdmin ? '治理后台' : '空间选择' }}</RouterLink
      >
    </header>
    <main id="profile-content" class="profile-body" tabindex="-1">
      <div class="profile-heading">
        <p class="section-label">( 账号资料 )</p>
        <h1>个人资料<span aria-hidden="true">。</span></h1>
        <p>账号资料与你当前进入哪个空间无关。</p>
      </div>
      <section class="setting-block" aria-labelledby="nickname-title">
        <div>
          <h2 id="nickname-title">展示昵称</h2>
          <p>
            登录账号
            <strong class="username">{{ authStore.currentUser?.username }}</strong> 不会随昵称改变。
          </p>
        </div>
        <form @submit.prevent="saveNickname" :aria-busy="nicknameSaving">
          <label for="profile-nickname">昵称</label>
          <input
            id="profile-nickname"
            v-model="nickname"
            name="nickname"
            maxlength="20"
            autocomplete="nickname"
            :disabled="busy"
            :aria-invalid="!!nicknameError"
            aria-describedby="nickname-hint nickname-feedback"
          />
          <p id="nickname-hint" class="field-hint">用于成员列表和观点中的展示，最多 20 个字符。</p>
          <button class="ui-button profile-submit" type="submit" :disabled="busy">
            {{ nicknameSaving ? '正在保存…' : '保存昵称' }}
          </button>
          <div id="nickname-feedback">
            <p v-if="nicknameError" class="feedback error" role="alert">{{ nicknameError }}</p>
            <p v-if="nicknameMessage" class="feedback" role="status">{{ nicknameMessage }}</p>
          </div>
        </form>
      </section>
      <section class="setting-block" aria-labelledby="password-title">
        <div>
          <h2 id="password-title">修改密码</h2>
          <p>保存成功后，当前与其他设备都需要使用新密码重新登录。</p>
        </div>
        <form @submit.prevent="savePassword" :aria-busy="passwordSaving">
          <label for="current-password">原密码</label>
          <input
            id="current-password"
            v-model="currentPassword"
            name="currentPassword"
            :type="showPasswords ? 'text' : 'password'"
            autocomplete="current-password"
            required
            :disabled="busy"
          />
          <label for="new-password">新密码</label>
          <input
            id="new-password"
            v-model="newPassword"
            name="newPassword"
            :type="showPasswords ? 'text' : 'password'"
            autocomplete="new-password"
            minlength="4"
            maxlength="64"
            required
            :disabled="busy"
            aria-describedby="password-hint password-feedback"
          />
          <p id="password-hint" class="field-hint">当前密码长度为 4 到 64 个字符。</p>
          <label for="confirm-password">确认新密码</label>
          <input
            id="confirm-password"
            v-model="confirmPassword"
            name="confirmPassword"
            :type="showPasswords ? 'text' : 'password'"
            autocomplete="new-password"
            required
            :disabled="busy"
            :aria-invalid="!!passwordError"
            aria-describedby="password-feedback"
          />
          <label class="password-visibility"
            ><input v-model="showPasswords" type="checkbox" :disabled="busy" />显示密码</label
          >
          <button class="ui-button profile-submit" type="submit" :disabled="busy">
            {{ passwordSaving ? '正在保存…' : '修改密码并重新登录' }}
          </button>
          <div id="password-feedback">
            <p v-if="passwordError" class="feedback error" role="alert">{{ passwordError }}</p>
          </div>
        </form>
      </section>
    </main>
  </div>
</template>
<style scoped>
.profile-page {
  min-height: 100svh;
  padding: 24px var(--gutter) 72px;
}
.profile-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 24px;
  padding-bottom: 22px;
  border-bottom: 1px solid var(--color-border);
}
.profile-header > a:last-child {
  min-height: 32px;
  color: var(--color-muted);
  font-size: 13px;
}
.profile-header > a:last-child:hover {
  color: var(--color-ink);
  text-decoration: underline;
  text-underline-offset: 4px;
}
.profile-body {
  width: min(880px, 100%);
  margin: 68px auto 0;
}
.section-label {
  font-family: var(--font-mono);
  color: var(--color-muted);
  font-size: 11px;
  letter-spacing: 0.12em;
}
.profile-heading h1 {
  margin-top: 12px;
  font: 900 clamp(34px, 4vw, 44px)/1.2 var(--font-display);
}
.profile-heading h1 span {
  color: var(--color-focus);
}
.profile-heading > p:last-child {
  margin-top: 14px;
  color: var(--color-muted);
  font-size: 14px;
}
.setting-block {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 430px);
  gap: 40px;
  margin-top: 44px;
  padding-top: 28px;
  border-top: 1px solid var(--color-border);
}
h2 {
  font: 900 21px/1.5 var(--font-display);
}
.setting-block > div p {
  max-width: 42ch;
  margin-top: 10px;
  color: var(--color-muted);
  font-size: 13px;
  line-height: 1.8;
  overflow-wrap: anywhere;
}
.username {
  font: inherit;
  font-family: var(--font-mono);
}
form {
  display: grid;
  align-content: start;
  gap: 10px;
  min-width: 0;
}
form > label {
  margin-top: 8px;
  color: var(--color-muted);
  font-size: 13px;
}
input:not([type='checkbox']) {
  width: 100%;
  min-width: 0;
  min-height: 46px;
  padding: 10px 14px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-control);
  background: var(--color-surface);
  color: var(--color-ink);
  font: inherit;
  font-size: 14px;
}
input:disabled {
  background: var(--color-surface-secondary);
  cursor: wait;
}
input[aria-invalid='true'] {
  border-color: var(--color-danger);
}
.field-hint {
  color: var(--color-muted);
  font-size: 11px;
}
.profile-submit {
  min-height: 44px;
  margin-top: 16px;
  padding: 10px 18px;
  border: 1px solid var(--color-ink);
  border-radius: var(--radius-action);
  background: transparent;
  color: var(--color-ink);
  text-align: left;
  cursor: pointer;
  font: inherit;
  font-size: 13px;
}
.profile-submit:disabled {
  opacity: 0.6;
  cursor: wait;
}
.password-visibility {
  display: flex;
  align-items: center;
  gap: 8px;
  width: fit-content;
  cursor: pointer;
}
.password-visibility input {
  margin: 0;
  accent-color: var(--color-focus);
}
.feedback {
  padding-top: 8px;
  color: var(--color-focus);
  font-size: 13px;
  line-height: 1.7;
  overflow-wrap: anywhere;
}
.feedback.error {
  color: var(--color-danger);
}
@media (max-width: 760px) {
  .profile-body {
    margin-top: 40px;
  }
  .setting-block {
    grid-template-columns: minmax(0, 1fr);
    gap: 20px;
    margin-top: 36px;
  }
}
@media (max-width: 600px) {
  .profile-page {
    padding: 20px 20px 56px;
  }
  .profile-header {
    gap: 16px;
  }
}
</style>
