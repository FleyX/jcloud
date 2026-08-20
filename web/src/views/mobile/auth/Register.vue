<script setup lang="ts">
/**
 * 移动端注册页
 */
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useUserStore } from '@/store/user'
import { register } from '@/api/auth'
import { cn } from '@/utils/cn'
import { Cloud, User, Lock, Mail, Smile, Eye, EyeOff } from '@lucide/vue'

const router = useRouter()
const userStore = useUserStore()

onMounted(() => {
  if (userStore.isLoggedIn) {
    router.push('/files')
  }
})

const form = reactive({
  username: '',
  nickname: '',
  email: '',
  password: '',
  confirmPassword: '',
})
const showPassword = ref(false)
const loading = ref(false)
const errorMsg = ref('')

async function handleRegister() {
  if (!form.username || !form.password) {
    errorMsg.value = '用户名和密码不能为空'
    return
  }
  if (form.password.length < 6) {
    errorMsg.value = '密码长度至少 6 位'
    return
  }
  if (form.password !== form.confirmPassword) {
    errorMsg.value = '两次输入的密码不一致'
    return
  }
  errorMsg.value = ''
  loading.value = true
  try {
    await register({
      username: form.username,
      password: form.password,
      email: form.email,
      nickname: form.nickname,
    })
    router.push('/login')
  } catch (error) {
    errorMsg.value = error instanceof Error ? error.message : '注册失败'
  } finally {
    loading.value = false
  }
}

function goLogin() {
  router.push('/login')
}
</script>

<template>
  <div class="flex min-h-full flex-col bg-gradient-to-br from-surface-50 to-primary-50 p-6">
    <div class="flex flex-1 flex-col items-center justify-center">
      <div class="mb-8 flex flex-col items-center gap-3">
        <div class="flex h-16 w-16 items-center justify-center rounded-2xl bg-primary-600 text-white shadow-soft">
          <Cloud class="h-8 w-8" />
        </div>
        <h1 class="text-2xl font-bold text-surface-900">
          注册 JCloud
        </h1>
        <p class="text-sm text-surface-500">
          创建您的个人账号
        </p>
      </div>

      <form
        class="w-full max-w-sm space-y-4"
        @submit.prevent="handleRegister"
      >
        <div>
          <label class="mb-1.5 block text-sm font-medium text-surface-700">用户名</label>
          <div class="relative">
            <User class="absolute left-3.5 top-1/2 h-[18px] w-[18px] -translate-y-1/2 text-surface-400" />
            <input
              v-model="form.username"
              type="text"
              placeholder="请输入用户名"
              class="w-full rounded-xl border border-surface-200 bg-white py-3 pl-10 pr-4 text-sm text-surface-900 outline-none transition-all focus:border-primary-500 focus:ring-2 focus:ring-primary-100"
            >
          </div>
        </div>

        <div>
          <label class="mb-1.5 block text-sm font-medium text-surface-700">昵称</label>
          <div class="relative">
            <Smile class="absolute left-3.5 top-1/2 h-[18px] w-[18px] -translate-y-1/2 text-surface-400" />
            <input
              v-model="form.nickname"
              type="text"
              placeholder="请输入昵称"
              class="w-full rounded-xl border border-surface-200 bg-white py-3 pl-10 pr-4 text-sm text-surface-900 outline-none transition-all focus:border-primary-500 focus:ring-2 focus:ring-primary-100"
            >
          </div>
        </div>

        <div>
          <label class="mb-1.5 block text-sm font-medium text-surface-700">邮箱</label>
          <div class="relative">
            <Mail class="absolute left-3.5 top-1/2 h-[18px] w-[18px] -translate-y-1/2 text-surface-400" />
            <input
              v-model="form.email"
              type="email"
              placeholder="请输入邮箱"
              class="w-full rounded-xl border border-surface-200 bg-white py-3 pl-10 pr-4 text-sm text-surface-900 outline-none transition-all focus:border-primary-500 focus:ring-2 focus:ring-primary-100"
            >
          </div>
        </div>

        <div>
          <label class="mb-1.5 block text-sm font-medium text-surface-700">密码</label>
          <div class="relative">
            <Lock class="absolute left-3.5 top-1/2 h-[18px] w-[18px] -translate-y-1/2 text-surface-400" />
            <input
              v-model="form.password"
              :type="showPassword ? 'text' : 'password'"
              placeholder="请输入密码（至少 6 位）"
              class="w-full rounded-xl border border-surface-200 bg-white py-3 pl-10 pr-10 text-sm text-surface-900 outline-none transition-all focus:border-primary-500 focus:ring-2 focus:ring-primary-100"
            >
            <button
              type="button"
              class="absolute right-3 top-1/2 -translate-y-1/2 text-surface-400 outline-none hover:text-surface-600"
              @click="showPassword = !showPassword"
            >
              <component
                :is="showPassword ? EyeOff : Eye"
                class="h-[18px] w-[18px]"
              />
            </button>
          </div>
        </div>

        <div>
          <label class="mb-1.5 block text-sm font-medium text-surface-700">确认密码</label>
          <div class="relative">
            <Lock class="absolute left-3.5 top-1/2 h-[18px] w-[18px] -translate-y-1/2 text-surface-400" />
            <input
              v-model="form.confirmPassword"
              :type="showPassword ? 'text' : 'password'"
              placeholder="请再次输入密码"
              class="w-full rounded-xl border border-surface-200 bg-white py-3 pl-10 pr-4 text-sm text-surface-900 outline-none transition-all focus:border-primary-500 focus:ring-2 focus:ring-primary-100"
            >
          </div>
        </div>

        <div
          v-if="errorMsg"
          class="rounded-lg bg-red-50 px-3 py-2 text-center text-sm font-medium text-red-600"
        >
          {{ errorMsg }}
        </div>

        <button
          type="submit"
          :disabled="loading"
          :class="
            cn(
              'w-full rounded-xl bg-primary-600 py-3.5 text-sm font-semibold text-white shadow-soft transition-all hover:bg-primary-700 hover:shadow-md active:scale-[0.98]',
              loading && 'cursor-not-allowed opacity-70'
            )
          "
        >
          {{ loading ? '注册中...' : '注 册' }}
        </button>
      </form>
    </div>

    <div class="pb-6 text-center text-sm text-surface-500">
      已有账号？
      <button
        class="font-medium text-primary-600 hover:text-primary-700"
        @click="goLogin"
      >
        返回登录
      </button>
    </div>
  </div>
</template>
