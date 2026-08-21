<script setup lang="ts">
/**
 * 海报卡片
 * - 展示海报图、标题、评分、进度条
 * - 未识别条目显示角标，可触发手动匹配
 * - 传入 ownerType/ownerId 时右上角显示收藏心形（已收藏实心高亮；未收藏 PC 端悬浮显现、移动端常显淡色）
 * - 传入 ownerId 时心形下方显示已观看 ✓ 角标（已观看常驻实心高亮；未观看 PC 端悬浮显现、移动端常显淡色），点击切换
 * - 已观看的卡片不显示观看进度条
 */
import { computed, ref, watch } from 'vue'
import { Check, Film, Heart } from '@lucide/vue'
import { toggleFavorite, updateMediaWatched } from '@/api/media'
import type { MediaFavoriteOwnerType } from '@/types/media'
import { cn } from '@/utils/cn'

interface Props {
  title: string
  posterUrl?: string | null
  voteAverage?: number | null
  releaseDate?: string | null
  progressMs?: number | null
  durationMs?: number | null
  unmatched?: boolean
  /** 收藏归属实体类型，传入则显示收藏心形 */
  ownerType?: MediaFavoriteOwnerType
  /** 收藏归属实体 ID（心形点击切换用） */
  ownerId?: string
  /** 初始收藏状态 */
  favorited?: boolean
  /** 初始已观看状态 */
  watched?: boolean
}

const props = defineProps<Props>()
const emit = defineEmits<{
  play: []
  /** 就地取消收藏成功（服务端返回 false）后通知父级，便于收藏页移除卡片 */
  unfavorite: []
}>()

const progressPercent = computed(() => {
  if (watched.value) return 0
  if (!props.progressMs || !props.durationMs || props.durationMs <= 0) return 0
  return Math.min(100, Math.round((props.progressMs / props.durationMs) * 100))
})

const ratingText = computed(() =>
  props.voteAverage != null && props.voteAverage > 0 ? props.voteAverage.toFixed(1) : null,
)

const yearText = computed(() => (props.releaseDate ? props.releaseDate.slice(0, 4) : null))

/** 是否启用收藏心形（墙组件传入 ownerType/ownerId 才显示，影视首页卡片不显示） */
const heartEnabled = computed(() => !!props.ownerType && !!props.ownerId)

const favorited = ref(props.favorited ?? false)
watch(
  () => props.favorited,
  (value) => {
    favorited.value = value ?? false
  },
)

const toggling = ref(false)

/** 是否启用已观看 ✓ 角标（有 ownerId 即启用，按 id 探测，ownerType 无需判断） */
const watchedEnabled = computed(() => !!props.ownerId)

const watched = ref(props.watched ?? false)
watch(
  () => props.watched,
  (value) => {
    watched.value = value ?? false
  },
)

const watchedToggling = ref(false)

/** 海报加载失败标志：加载失败视同无图走 v-else 占位（URL 变化时复位） */
const imgError = ref(false)
watch(
  () => props.posterUrl,
  () => {
    imgError.value = false
  },
)

/** 点击心形：本地先翻转，调 toggle 成功后以服务端结果为准，失败回滚（异常提示由统一请求层处理） */
async function toggle() {
  if (!props.ownerType || !props.ownerId || toggling.value) return
  const previous = favorited.value
  favorited.value = !previous
  toggling.value = true
  try {
    favorited.value = await toggleFavorite(props.ownerType, props.ownerId)
    if (!favorited.value) emit('unfavorite')
  } catch {
    favorited.value = previous
  } finally {
    toggling.value = false
  }
}

/** 点击 ✓：本地先翻转，调 update 成功保留、失败回滚（异常提示由统一请求层处理） */
async function toggleWatched() {
  if (!props.ownerId || watchedToggling.value) return
  const previous = watched.value
  watched.value = !previous
  watchedToggling.value = true
  try {
    await updateMediaWatched(props.ownerId, !previous)
  } catch {
    watched.value = previous
  } finally {
    watchedToggling.value = false
  }
}
</script>

<template>
  <div class="group flex flex-col">
    <div
      class="relative aspect-[2/3] w-full cursor-pointer overflow-hidden rounded-2xl bg-surface-100 shadow-soft transition-transform group-hover:scale-[1.02]"
      @click="emit('play')"
    >
      <img
        v-if="posterUrl && !imgError"
        :src="posterUrl"
        :alt="title"
        loading="lazy"
        class="h-full w-full object-cover"
        @error="imgError = true"
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
      <!-- 收藏心形角标：已收藏常显实心高亮；未收藏 PC 端悬浮显现、移动端常显淡色 -->
      <button
        v-if="heartEnabled"
        class="absolute right-2 top-9 z-10 flex h-8 w-8 items-center justify-center rounded-full bg-black/50 text-white backdrop-blur-sm transition-all hover:bg-black/70"
        :class="cn(
          favorited
            ? 'text-rose-500'
            : 'max-sm:opacity-70 sm:opacity-0 sm:group-hover:opacity-100'
        )"
        :title="favorited ? '取消收藏' : '收藏'"
        @click.stop="toggle"
      >
        <Heart
          class="h-4 w-4"
          :class="favorited && 'fill-rose-500'"
        />
      </button>
      <!-- 已观看 ✓ 角标：已观看常显实心高亮；未观看 PC 端悬浮显现、移动端常显淡色 -->
      <button
        v-if="watchedEnabled"
        class="absolute right-2 top-16 z-10 flex h-8 w-8 items-center justify-center rounded-full bg-black/50 text-white backdrop-blur-sm transition-all hover:bg-black/70"
        :class="cn(
          watched
            ? 'text-emerald-400'
            : 'max-sm:opacity-70 sm:opacity-0 sm:group-hover:opacity-100'
        )"
        :title="watched ? '标记未观看' : '标记已观看'"
        @click.stop="toggleWatched"
      >
        <Check
          class="h-4 w-4"
          :class="watched && 'fill-emerald-400'"
        />
      </button>
      <div class="absolute left-2 top-2 flex flex-col items-start gap-1">
        <span
          v-if="unmatched"
          class="rounded-lg bg-amber-500/90 px-1.5 py-0.5 text-xs font-medium text-white"
        >
          未识别
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
