<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowRight, Lock, User } from '@element-plus/icons-vue'
import {
  ElForm,
  ElFormItem,
  ElIcon,
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
const initialUsername = typeof route.query.username === 'string' ? route.query.username : ''
const form = reactive<LoginForm>({ username: initialUsername, password: '' })
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
  try {
    const valid = await formRef.value?.validate().catch(() => false)
    if (!valid) return

    await authStore.login(form)
    ElMessage.success('欢迎回来')
    const redirect =
      typeof route.query.redirect === 'string'
        ? route.query.redirect
        : authStore.isSuperAdmin
          ? '/admin/users'
          : '/workspaces'
    await router.replace(redirect)
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '暂时无法登录，请稍后重试')
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <AuthLayout>
    <p v-if="route.query.reason === 'password-changed'" class="login-notice" role="status">
      密码已更新，请使用新密码重新登录。
    </p>
    <div class="auth-form-heading">
      <p>欢迎回来</p>
      <h2>登录你的研究空间</h2>
      <span>查看共同关注，以及家人和伙伴最近更新的判断。</span>
    </div>

    <el-form
      ref="formRef"
      :model="form"
      :rules="rules"
      label-position="top"
      @submit.prevent="handleLogin"
    >
      <el-form-item label="登录账号" prop="username">
        <el-input
          v-model="form.username"
          :prefix-icon="User"
          placeholder="请输入登录账号"
          size="large"
        />
      </el-form-item>

      <el-form-item label="密码" prop="password">
        <el-input
          v-model="form.password"
          :prefix-icon="Lock"
          type="password"
          placeholder="请输入密码"
          size="large"
          show-password
        />
      </el-form-item>

      <button class="auth-submit-button" type="submit" :disabled="submitting">
        <span>{{ submitting ? '正在登录…' : '进入空间' }}</span>
        <el-icon v-if="!submitting"><ArrowRight /></el-icon>
      </button>
    </el-form>

    <p class="auth-switch-note">
      还没有账号？<button type="button" @click="router.push('/register')">创建账号</button>
    </p>
    <div class="auth-security-note">
      <span class="auth-security-mark">✓</span>登录信息只用于进入你的共享空间
    </div>
  </AuthLayout>
</template>

<style scoped>
.login-notice {
  margin-bottom: 24px;
  padding: 12px 14px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-control);
  color: var(--color-focus);
  line-height: 1.5;
}
</style>
