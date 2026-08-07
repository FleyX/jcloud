<script setup lang="ts">
/**
 * 媒体搜索结果行：小海报 + 标题 + 元信息
 */
import { Film } from '@lucide/vue'
import { ref, watch } from 'vue'
import { withToken } from '@/api/media'

interface Props {
  title: string
  posterUrl?: string | null
  subtitle?: string | null
}

const props = defineProps<Props>()

/** 海报加载失败标志：加载失败视同无图走 v-else 占位（URL 变化时复位） */
const imgError = ref(false)
watch(
  () => props.posterUrl,
  () => {
    imgError.value = false
  },
)
</script>

<template>
  <div class="flex items-center gap-3">
    <div class="h-14 w-10 shrink-0 overflow-hidden rounded-lg bg-surface-100">
      <img
        v-if="posterUrl && !imgError"
        :src="withToken(posterUrl)"
        :alt="title"
        loading="lazy"
        class="h-full w-full object-cover"
        @error="imgError = true"
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
