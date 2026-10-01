<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  ElForm,
  ElFormItem,
  ElInput,
  ElMessage,
  type FormInstance,
  type FormRules,
} from 'element-plus'
import 'element-plus/es/components/form/style/css'
import 'element-plus/es/components/form-item/style/css'
import 'element-plus/es/components/icon/style/css'
import 'element-plus/es/components/input/style/css'
import 'element-plus/es/components/message/style/css'
import AuthLayout from '@/components/AuthLayout.vue'
import { useAuthStore } from '@/stores/auth'

interface LoginForm {
  username: string
  password: string
}

const router = useRouter()
const route = useRoute()
const authStore = useAuthStore()
const formRef = ref<FormInstance>()
const submitting = ref(false)
const actionError = ref('')
const showPassword = ref(false)
let disposed = false
const registerRoute = computed(() => ({
  name: 'register',
  query: typeof route.query.redirect === 'string' ? { redirect: route.query.redirect } : {},
}))
const initialUsername = typeof route.query.username === 'string' ? route.query.username : ''
const form = reactive<LoginForm>({ username: initialUsername, password: '' })
watch(
  () => route.query.username,
  (value) => {
    if (!submitting.value && typeof value === 'string') form.username = value
  },
)
const rules: FormRules<LoginForm> = {
  username: [{ required: true, message: '请输入登录账号', trigger: 'blur' }],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 4, max: 64, message: '密码长度为4到64个字符', trigger: 'blur' },
  ],
}

async function handleLogin() {
  if (submitting.value) return

  submitting.value = true
  actionError.value = ''
  try {
    const valid = await formRef.value?.validate().catch(() => false)
    if (!valid || disposed) return

    const requestedRedirect = route.query.redirect
    const accepted = await authStore.login({ ...form })
    if (!accepted || disposed) return
    ElMessage({
      type: 'success',
      message: '欢迎回来',
      customClass: 'account-message research-theme',
    })
    const redirect =
      typeof requestedRedirect === 'string'
        ? requestedRedirect
        : authStore.isSuperAdmin
          ? '/admin/users'
          : '/workspaces'
    await router.replace(redirect)
  } catch (error) {
    if (!disposed)
      actionError.value = error instanceof Error ? error.message : '暂时无法登录，请稍后重试'
  } finally {
    submitting.value = false
  }
}
onBeforeUnmount(() => {
  disposed = true
  if (submitting.value) authStore.invalidateLogin()
})
</script>

<template>
  <AuthLayout>
    <p v-if="route.query.reason === 'password-changed'" class="account-feedback" role="status">
      密码已更新，请使用新密码重新登录。
    </p>
    <p v-else-if="route.query.reason === 'registered'" class="account-feedback" role="status">
      账号已创建，请使用刚才设置的密码登录。
    </p>
    <p v-else-if="route.query.reason === 'expired'" class="account-feedback" role="status">
      登录已过期，请重新登录后继续。
    </p>
    <div class="auth-form-heading">
      <h2>登录你的研究空间</h2>
    </div>

    <el-form
      ref="formRef"
      :model="form"
      :rules="rules"
      :disabled="submitting"
      :aria-busy="submitting"
      label-position="top"
      @submit.prevent="handleLogin"
    >
      <el-form-item label="登录账号" prop="username">
        <el-input
          v-model="form.username"
          id="login-username"
          name="username"
          autocomplete="username"
          placeholder="请输入登录账号"
          size="large"
        />
      </el-form-item>

      <el-form-item label="密码" prop="password">
        <el-input
          v-model="form.password"
          id="login-password"
          name="password"
          autocomplete="current-password"
          :type="showPassword ? 'text' : 'password'"
          placeholder="请输入密码"
          size="large"
        />
      </el-form-item>

      <label class="password-visibility"
        ><input v-model="showPassword" type="checkbox" :disabled="submitting" />显示密码</label
      >
      <p v-if="actionError" class="account-feedback error" role="alert">{{ actionError }}</p>
      <button class="auth-submit-button" type="submit" :disabled="submitting">
        <span>{{ submitting ? '正在登录…' : '进入空间' }}</span>
        <span v-if="!submitting" aria-hidden="true">→</span>
      </button>
    </el-form>

    <p class="auth-switch-note">
      还没有账号？<RouterLink :to="registerRoute">创建账号</RouterLink>
    </p>
    <div class="auth-security-note">使用个人账号进入，空间内保留各自的判断。</div>
  </AuthLayout>
</template>
