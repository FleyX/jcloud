<script setup lang="ts">
/**
 * 媒体搜索结果行：小海报 + 标题 + 元信息
 */
import { Film } from '@lucide/vue'
import { withToken } from '@/api/media'

interface Props {
  title: string
  posterUrl?: string | null
  subtitle?: string | null
}

defineProps<Props>()
</script>

<template>
  <div class="flex items-center gap-3">
    <div class="h-14 w-10 shrink-0 overflow-hidden rounded-lg bg-surface-100">
      <img
        v-if="posterUrl"
        :src="withToken(posterUrl)"
        :alt="title"
        loading="lazy"
        class="h-full w-full object-cover"
      >
      <div
        v-else
        class="flex h-full w-full items-center justify-center text-surface-300"
      >
        <Film class="h-4 w-4" />
      </div>
    </div>
    <div class="min-w-0 flex-1">
      <p class="truncate text-sm font-medium text-surface-800">
        {{ title }}
      </p>
      <p
        v-if="subtitle"
        class="truncate text-xs text-surface-400"
      >
        {{ subtitle }}
      </p>
    </div>
  </div>
</template>
