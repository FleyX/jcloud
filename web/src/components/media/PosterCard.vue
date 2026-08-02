<script setup lang="ts">
/**
 * 海报卡片
 * - 展示海报图、标题、评分、进度条
 * - 未识别条目显示角标，可触发手动匹配
 * - 元数据不完整条目显示弱标识
 */
import { computed } from 'vue'
import { Film } from '@lucide/vue'
import { withToken } from '@/api/media'

interface Props {
  title: string
  posterUrl?: string | null
  voteAverage?: number | null
  releaseDate?: string | null
  progressMs?: number
  durationMs?: number | null
  unmatched?: boolean
  /** 元数据不完整弱标识 */
  incomplete?: boolean
}

const props = defineProps<Props>()
const emit = defineEmits<{
  play: []
}>()

const progressPercent = computed(() => {
  if (!props.progressMs || !props.durationMs || props.durationMs <= 0) return 0
  return Math.min(100, Math.round((props.progressMs / props.durationMs) * 100))
})

const ratingText = computed(() =>
  props.voteAverage != null && props.voteAverage > 0 ? props.voteAverage.toFixed(1) : null,
)

const yearText = computed(() => (props.releaseDate ? props.releaseDate.slice(0, 4) : null))
</script>

<template>
  <div class="group flex flex-col">
    <div
      class="relative aspect-[2/3] w-full cursor-pointer overflow-hidden rounded-2xl bg-surface-100 shadow-soft transition-transform group-hover:scale-[1.02]"
      @click="emit('play')"
    >
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
        <Film class="h-12 w-12" />
      </div>

      <span
        v-if="ratingText"
        class="absolute right-2 top-2 rounded-lg bg-black/60 px-1.5 py-0.5 text-xs font-semibold text-amber-300"
      >
        {{ ratingText }}
      </span>
      <div class="absolute left-2 top-2 flex flex-col items-start gap-1">
        <span
          v-if="unmatched"
          class="rounded-lg bg-amber-500/90 px-1.5 py-0.5 text-xs font-medium text-white"
        >
          未识别
        </span>
        <span
          v-if="incomplete"
          class="rounded-lg bg-surface-800/90 px-1.5 py-0.5 text-xs font-medium text-white"
        >
          不完整
        </span>
      </div>

      <div
        v-if="progressPercent > 0"
        class="absolute bottom-0 left-0 h-1 w-full bg-black/40"
      >
        <div
          class="h-full bg-primary-500"
          :style="{ width: `${progressPercent}%` }"
        />
      </div>
    </div>

    <div class="mt-2 px-0.5">
      <p class="truncate text-sm font-medium text-surface-800">
        {{ title }}
      </p>
      <p
        v-if="yearText"
        class="text-xs text-surface-400"
      >
        {{ yearText }}
      </p>
    </div>
  </div>
</template>
