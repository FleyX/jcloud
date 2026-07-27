<script setup lang="ts">
/**
 * TMDB 全局配置卡片（管理员）
 */
import { onMounted, ref } from 'vue'
import { fetchTmdbConfig, updateTmdbConfig } from '@/api/media'
import { useNotificationStore } from '@/store/notification'

const notificationStore = useNotificationStore()

const apiKey = ref('')
const proxy = ref('')
const saving = ref(false)

onMounted(async () => {
  const config = await fetchTmdbConfig()
  apiKey.value = config.apiKey
  proxy.value = config.proxy
})

async function handleSave() {
  saving.value = true
  try {
    await updateTmdbConfig({ apiKey: apiKey.value.trim(), proxy: proxy.value.trim() })
    notificationStore.success('TMDB 配置已保存')
  } catch {
    // request.ts 已统一处理异常提示
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <div class="rounded-2xl border border-surface-100 bg-white p-6 shadow-soft">
    <h3 class="mb-1 text-base font-semibold text-surface-900">
      TMDB 配置
    </h3>
    <p class="mb-4 text-xs text-surface-400">
      影视媒体库的元数据来源，API Key 可在 themoviedb.org 免费申请
    </p>
    <div class="flex flex-wrap items-end gap-4">
      <label class="flex-1 min-w-64">
        <span class="mb-1 block text-xs font-medium text-surface-500">API Key</span>
        <input
          v-model="apiKey"
          type="text"
          placeholder="TMDB API Key"
          class="w-full rounded-xl border border-surface-200 bg-surface-50 px-4 py-2 text-sm outline-none focus:border-primary-300 focus:bg-white"
        >
      </label>
      <label class="w-64">
        <span class="mb-1 block text-xs font-medium text-surface-500">HTTP 代理（可选）</span>
        <input
          v-model="proxy"
          type="text"
          placeholder="host:port"
          class="w-full rounded-xl border border-surface-200 bg-surface-50 px-4 py-2 text-sm outline-none focus:border-primary-300 focus:bg-white"
        >
      </label>
      <button
        class="rounded-xl bg-primary-600 px-5 py-2 text-sm font-semibold text-white hover:bg-primary-700 disabled:bg-surface-300"
        :disabled="saving"
        @click="handleSave"
      >
        保存
      </button>
    </div>
  </div>
</template>
