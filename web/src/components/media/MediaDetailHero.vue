<script setup lang="ts">
/**
 * 媒体详情页头部（Jellyfin 风格：背景横幅 + 海报 + 元信息 + 操作按钮）
 * PC/移动端共用
 */
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { ArrowLeft, Film, Pencil, Play, RotateCcw, RefreshCw, Star } from '@lucide/vue'
import { withToken } from '@/api/media'
import { formatDurationText, formatPosition } from './format'

interface Props {
  title: string
  originalTitle?: string | null
  backdropUrl?: string | null
  posterUrl?: string | null
  releaseDate?: string | null
  voteAverage?: number | null
  genres?: string[]
  durationMs?: number | null
  overview?: string | null
  unmatched?: boolean
  /** 续播位置（毫秒），>0 时显示「继续播放 + 从头播放」 */
  continueMs?: number
  /** 文件信息标签（未识别简版详情用） */
  fileInfoChips?: string[]
  /** 是否显示刷新元数据按钮 */
  showRefresh?: boolean
}

const props = withDefaults(defineProps<Props>(), {
  genres: () => [],
  fileInfoChips: () => [],
  continueMs: 0,
})

const emit = defineEmits<{
  play: [startMs: number]
  rematch: []
  refresh: []
}>()

const yearText = computed(() => (props.releaseDate ? props.releaseDate.slice(0, 4) : null))
const durationText = computed(() => formatDurationText(props.durationMs))
const ratingText = computed(() =>
  props.voteAverage != null && props.voteAverage > 0 ? props.voteAverage.toFixed(1) : null,
)

const router = useRouter()

/** 返回上一页，无历史时回影视首页 */
function goBack() {
  if (window.history.state?.back) {
    router.back()
  } else {
    router.push('/media')
  }
}
</script>

<template>
  <div>
    <!-- 背景横幅 -->
    <div class="relative">
      <div class="absolute inset-0 overflow-hidden bg-surface-900">
        <img
          v-if="backdropUrl"
          :src="withToken(backdropUrl)"
          :alt="title"
          class="h-full w-full object-cover opacity-60"
        >
        <div class="absolute inset-0 bg-gradient-to-t from-surface-950 via-surface-950/60 to-surface-950/20" />
      </div>

      <!-- 返回按钮 -->
      <button
        class="absolute left-4 top-4 z-10 flex h-9 w-9 items-center justify-center rounded-full bg-black/30 text-white backdrop-blur-sm transition-colors hover:bg-black/50 md:left-6 md:top-6"
        title="返回"
        @click="goBack"
      >
        <ArrowLeft class="h-5 w-5" />
      </button>

      <div class="relative flex flex-col gap-4 px-4 pb-6 pt-16 md:flex-row md:items-end md:gap-8 md:px-10 md:pt-32">
        <!-- 海报 -->
        <div class="aspect-[2/3] w-32 shrink-0 overflow-hidden rounded-2xl bg-surface-800 shadow-lg md:w-48">
          <img
            v-if="posterUrl"
            :src="withToken(posterUrl)"
            :alt="title"
            class="h-full w-full object-cover"
          >
          <div
            v-else
            class="flex h-full w-full items-center justify-center text-surface-600"
          >
            <Film class="h-12 w-12" />
          </div>
        </div>

        <!-- 元信息 -->
        <div class="min-w-0 flex-1 text-white">
          <div class="flex items-center gap-2">
            <h1 class="truncate text-2xl font-bold md:text-3xl">
              {{ title }}
            </h1>
            <span
              v-if="unmatched"
              class="shrink-0 rounded-lg bg-amber-500/90 px-1.5 py-0.5 text-xs font-medium text-white"
            >
              未识别
            </span>
          </div>
          <p
            v-if="originalTitle && originalTitle !== title"
            class="mt-0.5 truncate text-sm text-surface-300"
          >
            {{ originalTitle }}
          </p>

          <div class="mt-2 flex flex-wrap items-center gap-x-3 gap-y-1 text-sm text-surface-200">
            <span
              v-if="ratingText"
              class="flex items-center gap-1 font-semibold text-amber-300"
            >
              <Star class="h-4 w-4 fill-amber-300" />{{ ratingText }}
            </span>
            <span v-if="yearText">{{ yearText }}</span>
            <span v-if="durationText">{{ durationText }}</span>
            <span
              v-for="genre in genres"
              :key="genre"
              class="rounded-lg bg-white/10 px-2 py-0.5 text-xs"
            >
              {{ genre }}
            </span>
            <span
              v-for="chip in fileInfoChips"
              :key="chip"
              class="rounded-lg bg-white/10 px-2 py-0.5 text-xs"
            >
              {{ chip }}
            </span>
          </div>

          <!-- 操作按钮 -->
          <div class="mt-4 flex flex-wrap items-center gap-2">
            <button
              class="flex items-center gap-1.5 rounded-xl bg-primary-600 px-5 py-2 text-sm font-semibold text-white hover:bg-primary-700"
              @click="emit('play', continueMs > 0 ? continueMs : 0)"
            >
              <Play class="h-4 w-4 fill-white" />
              {{ continueMs > 0 ? `继续播放 (${formatPosition(continueMs)})` : '播放' }}
            </button>
            <button
              v-if="continueMs > 0"
              class="flex items-center gap-1.5 rounded-xl bg-white/10 px-4 py-2 text-sm font-medium text-white hover:bg-white/20"
              @click="emit('play', 0)"
            >
              <RotateCcw class="h-4 w-4" />
              从头播放
            </button>
            <button
              class="flex items-center gap-1.5 rounded-xl bg-white/10 px-3 py-2 text-sm text-white hover:bg-white/20"
              title="修正匹配"
              @click="emit('rematch')"
            >
              <Pencil class="h-4 w-4" />
            </button>
            <button
              v-if="showRefresh"
              class="flex items-center gap-1.5 rounded-xl bg-white/10 px-3 py-2 text-sm text-white hover:bg-white/20"
              title="刷新元数据"
              @click="emit('refresh')"
            >
              <RefreshCw class="h-4 w-4" />
            </button>
          </div>
        </div>
      </div>
    </div>

    <!-- 简介 -->
    <p
      v-if="overview"
      class="mt-4 px-4 text-sm leading-6 text-surface-600 md:px-10"
    >
      {{ overview }}
    </p>
  </div>
</template>
