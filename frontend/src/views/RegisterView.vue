<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ArrowRight, EditPen, Lock, User } from '@element-plus/icons-vue'
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
import { register } from '@/api/auth'
import AuthLayout from '@/components/AuthLayout.vue'

interface RegisterForm {
  username: string
  nickname: string
  password: string
  confirmPassword: string
}

const router = useRouter()
const formRef = ref<FormInstance>()
const submitting = ref(false)
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
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return

  submitting.value = true
  try {
    const result = (
      await register({
        username: form.username,
        nickname: form.nickname,
        password: form.password,
      })
    ).data
    if (result.code !== 'SUCCESS') throw new Error(result.message || '注册失败，请稍后重试')

    ElMessage.success('账号创建成功，请登录')
    await router.replace({ name: 'login', query: { username: result.data.username } })
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '暂时无法注册，请稍后重试')
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <AuthLayout>
    <div class="auth-form-heading">
      <p>开始记录</p>
      <h2>创建你的研究账号</h2>
      <span>建立个人身份，再通过邀请加入家人或伙伴的共享空间。</span>
    </div>

    <el-form
      ref="formRef"
      :model="form"
      :rules="rules"
      label-position="top"
      @submit.prevent="handleRegister"
    >
      <el-form-item label="登录账号" prop="username">
        <el-input
          v-model="form.username"
          :prefix-icon="User"
          placeholder="4-20位字母、数字或下划线"
          size="large"
        />
      </el-form-item>

      <el-form-item label="昵称" prop="nickname">
        <el-input
          v-model="form.nickname"
          :prefix-icon="EditPen"
          placeholder="伙伴们看到的称呼"
          size="large"
        />
      </el-form-item>

      <el-form-item label="密码" prop="password">
        <el-input
          v-model="form.password"
          :prefix-icon="Lock"
          type="password"
          placeholder="至少4个字符"
          size="large"
          show-password
        />
      </el-form-item>

      <el-form-item label="确认密码" prop="confirmPassword">
        <el-input
          v-model="form.confirmPassword"
          :prefix-icon="Lock"
          type="password"
          placeholder="再次输入密码"
          size="large"
          show-password
          @keyup.enter="handleRegister"
        />
      </el-form-item>

      <button class="auth-submit-button" type="submit" :disabled="submitting">
        <span>{{ submitting ? '正在创建…' : '创建账号' }}</span>
        <el-icon v-if="!submitting"><ArrowRight /></el-icon>
      </button>
    </el-form>

    <p class="auth-switch-note">
      已有账号？<button type="button" @click="router.push('/login')">返回登录</button>
    </p>
    <div class="auth-security-note">
      <span class="auth-security-mark">✓</span>账号状态和创建时间由系统自动管理
    </div>
  </AuthLayout>
</template>
