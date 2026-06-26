<script setup lang="ts">
/**
 * 移动端登录页
 */
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useUserStore } from '@/store/user'
import { cn } from '@/utils/cn'
import { Cloud, User, Lock, Eye, EyeOff } from '@lucide/vue'

const router = useRouter()
const userStore = useUserStore()

onMounted(() => {
  if (userStore.token) {
    router.push('/files')
  }
})

const form = reactive({
  username: '',
  password: '',
})
const showPassword = ref(false)
const loading = ref(false)
const errorMsg = ref('')

async function handleLogin() {
  if (!form.username || !form.password) {
    errorMsg.value = '请输入用户名和密码'
    return
  }
  errorMsg.value = ''
  loading.value = true
  try {
    const data = await userStore.loginAction(form.username, form.password)
    if (data.userInfo.isAdmin && !data.initialized) {
      router.push('/init')
    } else {
      router.push('/')
    }
  } catch (error) {
    errorMsg.value = error instanceof Error ? error.message : '登录失败'
  } finally {
    loading.value = false
  }
}

function goRegister() {
  router.push('/register')
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
          欢迎回到 JCloud
        </h1>
        <p class="text-sm text-surface-500">
          请输入账号密码登录系统
        </p>
      </div>

      <form
        class="w-full max-w-sm space-y-5"
        @submit.prevent="handleLogin"
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
          <label class="mb-1.5 block text-sm font-medium text-surface-700">密码</label>
          <div class="relative">
            <Lock class="absolute left-3.5 top-1/2 h-[18px] w-[18px] -translate-y-1/2 text-surface-400" />
            <input
              v-model="form.password"
              :type="showPassword ? 'text' : 'password'"
              placeholder="请输入密码"
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
          {{ loading ? '登录中...' : '登 录' }}
        </button>
      </form>
    </div>

    <div class="pb-6 text-center text-sm text-surface-500">
      还没有账号？
      <button
        class="font-medium text-primary-600 hover:text-primary-700"
        @click="goRegister"
      >
        立即注册
      </button>
    </div>
  </div>
</template>
