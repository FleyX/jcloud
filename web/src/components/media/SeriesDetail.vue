<script setup lang="ts">
/**
 * 电视剧详情页（PC/移动端共用）
 * - 头部播放按钮定位到「下一集待看」
 * - 剧集列表点击即播
 */
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { LoaderCircle } from '@lucide/vue'
import type { MediaItemVo, MediaSeriesDetailVo, TmdbSearchResultVo } from '@/types/media'
import { fetchSeriesDetail, refreshMetadata, updateSeriesMatch } from '@/api/media'
import { useNotificationStore } from '@/store/notification'
import { formatDurationText } from './format'
import MediaDetailHero from './MediaDetailHero.vue'
import TmdbMatchModal from './TmdbMatchModal.vue'

const route = useRoute()
const router = useRouter()
const notificationStore = useNotificationStore()

const seriesName = route.params.seriesName as string
const detail = ref<MediaSeriesDetailVo | null>(null)
const loading = ref(true)
const matchOpen = ref(false)

onMounted(load)

async function load() {
  loading.value = true
  try {
    detail.value = await fetchSeriesDetail(seriesName)
  } finally {
    loading.value = false
  }
}

const unmatched = computed(() => detail.value?.matchStatus === 'unmatched')

/**
 * 下一集待看：第一集未看到 95% 的集，全部看完则为第一集
 */
const nextUp = computed<MediaItemVo | null>(() => {
  const episodes = detail.value?.episodes ?? []
  if (episodes.length === 0) return null
  return episodes.find((e) => {
    if (!e.progressMs || e.progressMs <= 0) return true
    if (!e.durationMs || e.durationMs <= 0) return false
    return e.progressMs < e.durationMs * 0.95
  }) ?? episodes[0]
})

function episodeLabel(episode: MediaItemVo): string {
  if (episode.seasonNo != null && episode.episodeNo != null) {
    return `S${String(episode.seasonNo).padStart(2, '0')}E${String(episode.episodeNo).padStart(2, '0')}`
  }
  return '未知集'
}

function progressPercent(episode: MediaItemVo): number {
  if (!episode.progressMs || !episode.durationMs || episode.durationMs <= 0) return 0
  return Math.min(100, Math.round((episode.progressMs / episode.durationMs) * 100))
}

function handleHeroPlay(startMs: number) {
  if (nextUp.value) {
    playEpisode(nextUp.value, startMs)
  }
}

function playEpisode(episode: MediaItemVo, startMs?: number) {
  const resumeMs = startMs ?? episode.progressMs ?? 0
  router.push({
    name: 'MediaPlay',
    params: { id: episode.id },
    query: resumeMs > 0 ? { startMs: resumeMs } : {},
  })
}

async function handleMatched(result: TmdbSearchResultVo) {
  await updateSeriesMatch(seriesName, result.tmdbId)
  matchOpen.value = false
  await load()
}

async function handleRefresh() {
  if (!detail.value?.metadataId) return
  await refreshMetadata(detail.value.metadataId)
  notificationStore.success('元数据已刷新')
  await load()
}
</script>

<template>
  <div class="min-h-full bg-white">
    <p
      v-if="loading"
      class="flex items-center justify-center gap-2 py-24 text-sm text-surface-400"
    >
      <LoaderCircle class="h-4 w-4 animate-spin" />加载中…
    </p>
    <template v-else-if="detail">
      <MediaDetailHero
        :title="detail.title"
        :original-title="detail.originalTitle"
        :backdrop-url="detail.backdropUrl"
        :poster-url="detail.posterUrl"
        :release-date="detail.releaseDate"
        :vote-average="detail.voteAverage"
        :genres="detail.genres"
        :overview="detail.overview"
        :unmatched="unmatched"
        :continue-ms="nextUp?.progressMs ?? 0"
        :show-refresh="!!detail.metadataId"
        @play="handleHeroPlay"
        @rematch="matchOpen = true"
        @refresh="handleRefresh"
      />

      <!-- 剧集列表 -->
      <div class="mt-6 px-4 pb-8 md:px-10">
        <h2 class="mb-3 text-base font-semibold text-surface-900">
          剧集（{{ detail.episodes.length }}）
        </h2>
        <div class="divide-y divide-surface-100 rounded-2xl border border-surface-100">
          <button
            v-for="episode in detail.episodes"
            :key="episode.id"
            class="flex w-full items-center gap-4 px-4 py-3 text-left transition-colors hover:bg-surface-50"
            @click="playEpisode(episode)"
          >
            <span class="w-16 shrink-0 rounded-lg bg-surface-100 px-2 py-1 text-center text-xs font-semibold text-surface-600">
              {{ episodeLabel(episode) }}
            </span>
            <div class="min-w-0 flex-1">
              <p class="truncate text-sm font-medium text-surface-800">
                {{ episode.fileName }}
              </p>
              <div class="mt-1 flex items-center gap-2">
                <div
                  v-if="progressPercent(episode) > 0"
                  class="h-1 w-24 overflow-hidden rounded-full bg-surface-100"
                >
                  <div
                    class="h-full bg-primary-500"
                    :style="{ width: `${progressPercent(episode)}%` }"
                  />
                </div>
                <span
                  v-if="formatDurationText(episode.durationMs)"
                  class="text-xs text-surface-400"
                >
                  {{ formatDurationText(episode.durationMs) }}
                </span>
              </div>
            </div>
          </button>
        </div>
      </div>
    </template>

    <TmdbMatchModal
      :open="matchOpen"
      media-type="tv"
      :initial-query="detail?.title ?? ''"
      @close="matchOpen = false"
      @select="handleMatched"
    />
  </div>
</template>
