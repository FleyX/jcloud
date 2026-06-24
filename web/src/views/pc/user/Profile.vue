<script setup lang="ts">
import { onMounted } from 'vue'
import { useRouter } from 'vue-router'

const router = useRouter()

onMounted(() => {
  // PC 端个人中心功能通过顶部 Header 的下拉菜单实现，个人中心页为移动端专用
  router.replace('/files')
})
</script>

<template>
  <div class="flex h-full items-center justify-center text-surface-500">
    <span class="text-sm">正在跳转…</span>
  </div>
</template>
