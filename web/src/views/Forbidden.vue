<script setup lang="ts">
import { useRouter } from 'vue-router'

const router = useRouter()

function goHome() {
  router.push('/')
}
</script>

<template>
  <div class="flex h-full flex-col items-center justify-center gap-6 text-center">
    <div class="text-9xl font-black text-surface-200">
      403
    </div>
    <h1 class="text-2xl font-semibold text-surface-800">
      权限不足
    </h1>
    <p class="max-w-sm text-surface-500">
      抱歉，您没有权限访问该页面。
    </p>
    <button
      class="rounded-xl bg-primary-600 px-5 py-2.5 text-sm font-medium text-white shadow-soft transition-colors hover:bg-primary-700"
      @click="goHome"
    >
      返回首页
    </button>
  </div>
</template>
