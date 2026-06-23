<script setup lang="ts">
import { useRouter } from 'vue-router'

const router = useRouter()

function goHome() {
  router.push('/')
}

function goLogin() {
  router.push('/login')
}
</script>

<template>
  <div class="flex h-full flex-col items-center justify-center gap-6 text-center">
    <div class="text-9xl font-black text-surface-200">404</div>
    <h1 class="text-2xl font-semibold text-surface-800">页面不存在</h1>
    <p class="max-w-sm text-surface-500">抱歉，您访问的页面已离开地球，或您没有权限查看。</p>
    <div class="flex gap-3">
      <button
        class="rounded-xl bg-primary-600 px-5 py-2.5 text-sm font-medium text-white shadow-soft transition-colors hover:bg-primary-700"
        @click="goHome"
      >
        返回首页
      </button>
      <button
        class="rounded-xl border border-surface-200 bg-white px-5 py-2.5 text-sm font-medium text-surface-700 transition-colors hover:bg-surface-50"
        @click="goLogin"
      >
        去登录
      </button>
    </div>
  </div>
</template>
