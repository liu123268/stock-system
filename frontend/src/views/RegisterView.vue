<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElForm, ElFormItem, ElInput, type FormInstance, type FormRules } from 'element-plus'
import 'element-plus/es/components/form/style/css'
import 'element-plus/es/components/form-item/style/css'
import 'element-plus/es/components/input/style/css'
import { register } from '@/api/auth'
import AuthLayout from '@/components/AuthLayout.vue'

interface RegisterForm {
  username: string
  nickname: string
  password: string
  confirmPassword: string
}

const router = useRouter()
const route = useRoute()
const formRef = ref<FormInstance>()
const submitting = ref(false)
const actionError = ref('')
const showPassword = ref(false)
let disposed = false
const loginQuery = computed(() =>
  typeof route.query.redirect === 'string' ? { redirect: route.query.redirect } : {},
)
const form = reactive<RegisterForm>({
  username: '',
  nickname: '',
  password: '',
  confirmPassword: '',
})

const rules: FormRules<RegisterForm> = {
  username: [
    { required: true, message: '请输入登录账号', trigger: 'blur' },
    { min: 4, max: 20, message: '登录账号长度为4到20个字符', trigger: 'blur' },
    { pattern: /^[A-Za-z0-9_]+$/, message: '只能使用字母、数字和下划线', trigger: 'blur' },
  ],
  nickname: [
    { required: true, message: '请输入昵称', trigger: 'blur' },
    { max: 20, message: '昵称最多20个字符', trigger: 'blur' },
  ],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 4, max: 64, message: '密码长度为4到64个字符', trigger: 'blur' },
  ],
  confirmPassword: [
    { required: true, message: '请再次输入密码', trigger: 'blur' },
    {
      validator: (_rule, value: string, callback) => {
        if (value !== form.password) callback(new Error('两次输入的密码不一致'))
        else callback()
      },
      trigger: ['blur', 'change'],
    },
  ],
}

async function handleRegister() {
  if (submitting.value) return
  submitting.value = true
  actionError.value = ''
  try {
    const valid = await formRef.value?.validate().catch(() => false)
    if (!valid || disposed) return
    const returnQuery = { ...loginQuery.value }
    const result = (
      await register({
        username: form.username,
        nickname: form.nickname,
        password: form.password,
      })
    ).data
    if (disposed) return
    if (result.code !== 'SUCCESS') throw new Error(result.message || '注册失败，请稍后重试')

    await router.replace({
      name: 'login',
      query: { ...returnQuery, username: result.data.username, reason: 'registered' },
    })
  } catch (error) {
    if (!disposed)
      actionError.value = error instanceof Error ? error.message : '暂时无法注册，请稍后重试'
  } finally {
    submitting.value = false
  }
}
onBeforeUnmount(() => {
  disposed = true
})
</script>

<template>
  <AuthLayout mode="register">
    <div class="auth-form-heading">
      <h2>创建你的研究账号</h2>
    </div>

    <el-form
      ref="formRef"
      :model="form"
      :rules="rules"
      :disabled="submitting"
      :aria-busy="submitting"
      label-position="top"
      @submit.prevent="handleRegister"
    >
      <div class="auth-fields auth-fields--paired">
        <el-form-item label="登录账号" prop="username">
          <el-input
            v-model="form.username"
            id="register-username"
            name="username"
            autocomplete="username"
            placeholder="4–20位账号"
            aria-description="登录账号为4到20位字母、数字或下划线"
            size="large"
          />
        </el-form-item>

        <el-form-item label="昵称" prop="nickname">
          <el-input
            v-model="form.nickname"
            id="register-nickname"
            name="nickname"
            autocomplete="nickname"
            placeholder="伙伴看到的称呼"
            size="large"
          />
        </el-form-item>

        <el-form-item label="密码" prop="password">
          <el-input
            v-model="form.password"
            id="register-password"
            name="password"
            autocomplete="new-password"
            :type="showPassword ? 'text' : 'password'"
            placeholder="至少4个字符"
            size="large"
          />
        </el-form-item>

        <el-form-item label="确认密码" prop="confirmPassword">
          <el-input
            v-model="form.confirmPassword"
            id="register-confirm"
            name="confirmPassword"
            autocomplete="new-password"
            :type="showPassword ? 'text' : 'password'"
            placeholder="再次输入密码"
            size="large"
          />
        </el-form-item>
      </div>
      <label class="password-visibility"
        ><input v-model="showPassword" type="checkbox" :disabled="submitting" />显示密码</label
      >
      <p v-if="actionError" class="account-feedback error" role="alert">{{ actionError }}</p>
      <button class="ui-button auth-submit-button" type="submit" :disabled="submitting">
        <span>{{ submitting ? '正在创建…' : '创建账号' }}</span>
        <span v-if="!submitting" aria-hidden="true">→</span>
      </button>
    </el-form>

    <p class="auth-switch-note">
      已有账号？<RouterLink :to="{ name: 'login', query: loginQuery }">返回登录</RouterLink>
    </p>
    <div class="auth-security-note">昵称用于空间内展示，登录账号保持不变。</div>
  </AuthLayout>
</template>
