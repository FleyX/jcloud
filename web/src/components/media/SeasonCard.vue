<script setup lang="ts">
/**
 * 季卡片（电视剧详情页季网格中的单个卡片）
 * - 展示季海报/占位、标题与集数、「在看」胶囊、收藏心形、已观看 ✓ 角标
 * - 收藏/已观看切换按 PosterCard 的自治模式就地调 API；成功后 emit 通知父级静默重拉详情保持聚合一致，失败回滚
 * - 点击卡片 emit open 进入该季剧集列表
 */
import { ref, watch } from 'vue'
import { Check, Heart, Tv } from '@lucide/vue'
import type { MediaSeriesSeasonVo } from '@/types/media'
import { toggleFavorite, updateMediaWatched } from '@/api/media'
import { cn } from '@/utils/cn'
import { useOptimisticToggle } from '@/composables/useOptimisticToggle'

const props = defineProps<{ season: MediaSeriesSeasonVo }>()

const emit = defineEmits<{
  open: []
  /** 收藏/已观看切换成功后通知父级静默重拉详情（保持父级聚合一致） */
  updated: []
}>()

function seasonTitle() {
  return props.season.seasonNo != null ? `第 ${props.season.seasonNo} 季` : '未知季'
}

/** 已观看/收藏本地副本：props 只读，切换在副本上乐观置位；父级成功后重拉详情会以新对象替换并同步回副本 */
const watched = ref(!!props.season.watched)
const favorited = ref(!!props.season.favorited)
watch(
  () => props.season.watched,
  (value) => {
    watched.value = !!value
  },
)
watch(
  () => props.season.favorited,
  (value) => {
    favorited.value = !!value
  },
)

/** 季海报加载失败标志：失败视同无图走 v-else 占位（海报 URL 变化时复位） */
const posterError = ref(false)
watch(
  () => props.season.posterUrl,
  () => {
    posterError.value = false
  },
)

const { toggle: toggleWatched } = useOptimisticToggle({
  isWatched: () => watched.value,
  setWatched: (value) => {
    watched.value = value
  },
  toggle: (value) => updateMediaWatched(props.season.seasonId, value),
  onSuccess: () => emit('updated'),
})

/** 收藏/取消收藏：本地先翻转，成功后以服务端结果为准，失败回滚（异常提示由统一请求层处理） */
async function toggleSeasonFavorite() {
  const previous = favorited.value
  favorited.value = !previous
  try {
    favorited.value = await toggleFavorite('season', props.season.seasonId)
    emit('updated')
  } catch {
    favorited.value = previous
  }
}
</script>

<template>
  <div
    class="group cursor-pointer text-left"
    @click="emit('open')"
  >
    <div class="relative aspect-[2/3] w-full overflow-hidden rounded-2xl bg-surface-100 shadow-soft transition-transform group-hover:scale-[1.02]">
      <img
        v-if="season.posterUrl && !posterError"
        :src="season.posterUrl"
        :alt="seasonTitle()"
        loading="lazy"
        class="h-full w-full object-cover"
        @error="posterError = true"
      >
      <div
        v-else
        class="flex h-full w-full flex-col items-center justify-center gap-1 text-surface-300"
      >
        <Tv class="h-10 w-10" />
        <span class="text-xs text-surface-400">{{ seasonTitle() }}</span>
      </div>
      <span
        v-if="season.hasProgress"
        class="absolute left-2 top-2 rounded-lg bg-primary-600/90 px-1.5 py-0.5 text-xs font-medium text-white"
      >
        在看
      </span>
      <!-- 季卡片收藏心形：已收藏常显实心高亮；未收藏 PC 端悬浮显现、移动端常显淡色 -->
      <button
        class="absolute right-2 top-2 z-10 flex h-8 w-8 items-center justify-center rounded-full bg-black/50 text-white backdrop-blur-sm transition-all hover:bg-black/70"
        :class="cn(
          favorited
            ? 'text-rose-500'
            : 'max-sm:opacity-70 sm:opacity-0 sm:group-hover:opacity-100'
        )"
        :title="favorited ? '取消收藏' : '收藏'"
        @click.stop="toggleSeasonFavorite"
      >
        <Heart
          class="h-4 w-4"
          :class="favorited && 'fill-rose-500'"
        />
      </button>
      <!-- 季卡片已观看 ✓ 角标：已观看常显实心高亮；未观看 PC 端悬浮显现、移动端常显淡色 -->
      <button
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
    </div>
    <p class="mt-2 px-0.5 text-sm font-medium text-surface-800">
      {{ seasonTitle() }}
    </p>
    <p class="px-0.5 text-xs text-surface-400">
      共 {{ season.episodeCount }} 集
    </p>
  </div>
</template>
