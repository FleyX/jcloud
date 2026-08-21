<script setup lang="ts">
/**
 * 电影详情页（PC/移动端共用）
 * - 未识别条目展示文件信息简版详情，可修正匹配
 * - 多版本电影展示版本列表（分辨率/编码/大小等），点击版本进入播放页并携带 versionId；
 *   默认播放（播放按钮）不指定版本，由后端按续播语义定位；「默认」版本按后端 defaultVersionId 标记
 * - 仅一个版本时不渲染版本列表，播放按钮直接播默认版本
 */
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Clapperboard, Play } from '@lucide/vue'
import type { MediaItemDetailVo, MediaMovieVersionVo, TmdbSearchResultVo } from '@/types/media'
import { fetchItemDetail, refreshMetadata, toggleFavorite, updateMediaMatch, updateMediaWatched, type MediaRefreshMode } from '@/api/media'
import { formatSize } from '@/utils/fileDisplay'
import { cn } from '@/utils/cn'
import { useNotificationStore } from '@/store/notification'
import { useOptimisticToggle } from '@/composables/useOptimisticToggle'
import { formatDurationText } from './format'
import MediaDetailHero from './MediaDetailHero.vue'
import TmdbMatchModal from './TmdbMatchModal.vue'

const route = useRoute()
const router = useRouter()
const notificationStore = useNotificationStore()

const itemId = route.params.id as string
const detail = ref<MediaItemDetailVo | null>(null)
const loading = ref(true)
const matchOpen = ref(false)

onMounted(load)

async function load() {
  loading.value = true
  try {
    detail.value = await fetchItemDetail(itemId)
  } finally {
    loading.value = false
  }
}

const unmatched = computed(() => detail.value?.matchStatus === 'unmatched')

const versions = computed(() => detail.value?.versions ?? [])

const fileInfoChips = computed(() => {
  const d = detail.value
  if (!d || !unmatched.value) return []
  const chips: string[] = []
  if (d.fileSize != null) chips.push(formatSize(d.fileSize))
  if (d.width && d.height) chips.push(`${d.width}×${d.height}`)
  if (d.videoCodec) chips.push(d.videoCodec.toUpperCase())
  if (d.audioCodec) chips.push(d.audioCodec.toUpperCase())
  return chips
})

/** 版本文件事实标签：分辨率/封装/编码/大小/时长 */
function versionChips(version: MediaMovieVersionVo): string {
  const parts: string[] = []
  if (version.width && version.height) parts.push(`${version.width}×${version.height}`)
  if (version.container) parts.push(version.container.toUpperCase())
  if (version.videoCodec) parts.push(version.videoCodec.toUpperCase())
  if (version.audioCodec) parts.push(version.audioCodec.toUpperCase())
  if (version.fileSize) parts.push(formatSize(version.fileSize))
  const duration = formatDurationText(version.durationMs ? Number(version.durationMs) : null)
  if (duration) parts.push(duration)
  return parts.join(' · ')
}

function handlePlay(startMs: number) {
  router.push({ name: 'MediaPlay', params: { id: itemId }, query: startMs > 0 ? { startMs } : {} })
}

/** 播放指定版本：进入播放页并携带 versionId，后端用该版本文件事实播放 */
function playVersion(version: MediaMovieVersionVo) {
  router.push({ name: 'MediaPlay', params: { id: itemId }, query: { versionId: version.id } })
}

/** 是否为后端缺省播放版本（defaultVersionId 匹配） */
function isDefaultVersion(version: MediaMovieVersionVo): boolean {
  return detail.value?.defaultVersionId === version.id
}

async function handleMatched(result: TmdbSearchResultVo) {
  await updateMediaMatch(itemId, result.tmdbId, 'movie')
  matchOpen.value = false
  await load()
}

async function handleRefresh(mode: MediaRefreshMode) {
  if (!detail.value?.metadataId) return
  await refreshMetadata(detail.value.metadataId, mode)
  notificationStore.success(mode === 'force' ? '元数据已强制刷新' : '元数据已刷新')
  await load()
}

/** 收藏/取消收藏：本地先翻转，成功后以服务端结果为准，失败回滚（异常提示由统一请求层处理） */
async function toggleMovieFavorite() {
  if (!detail.value) return
  const previous = detail.value.favorited
  detail.value.favorited = !previous
  try {
    detail.value.favorited = await toggleFavorite('movie', itemId)
  } catch {
    detail.value.favorited = previous
  }
}

/** 标记/取消已观看：本地先翻转，成功后保留、失败回滚；标记已观看时同步清零进度（使播放按钮文案回退为「播放」） */
const toggleMovieWatched = useOptimisticToggle({
  isWatched: () => !!detail.value?.watched,
  setWatched: (watched) => {
    if (detail.value) detail.value.watched = watched
  },
  progress: {
    get: () => detail.value?.progressMs ?? null,
    set: (value) => {
      if (detail.value) detail.value.progressMs = value ?? 0
    },
  },
  toggle: (watched) => updateMediaWatched(itemId, watched),
}).toggle
</script>

<template>
  <div class="min-h-full bg-white">
    <p
      v-if="loading"
      class="py-24 text-center text-sm text-surface-400"
    >
      加载中…
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
        :duration-ms="detail.durationMs"
        :overview="detail.overview"
        :unmatched="unmatched"
        :continue-ms="detail.progressMs"
        :file-info-chips="fileInfoChips"
        :show-refresh="!!detail.metadataId"
        :favorited="detail.favorited"
        :watched="detail.watched"
        @play="handlePlay"
        @rematch="matchOpen = true"
        @refresh="handleRefresh"
        @toggle-favorite="toggleMovieFavorite"
        @toggle-watched="toggleMovieWatched"
      />

      <!-- 单版本或无版本时展示文件名；单版本由播放按钮直接播默认版本 -->
      <p
        v-if="versions.length <= 1"
        class="mt-2 px-4 text-xs text-surface-400 md:px-10"
      >
        {{ detail.fileName }}
      </p>

      <!-- 版本列表（电影多版本）：点击播放该版本，默认版本按后端 defaultVersionId 标记 -->
      <div
        v-else
        class="mt-4 px-4 pb-2 md:px-10"
      >
        <h2 class="text-base font-semibold text-surface-900">
          版本（{{ versions.length }}）
        </h2>
        <div class="mt-3 divide-y divide-surface-100 rounded-2xl border border-surface-100">
          <button
            v-for="version in versions"
            :key="version.id"
            :class="cn(
              'group flex w-full items-center gap-3 px-3 py-3 text-left transition-colors hover:bg-surface-50 md:gap-4 md:px-4',
              isDefaultVersion(version) && 'bg-primary-50/60'
            )"
            @click="playVersion(version)"
          >
            <div class="flex h-10 w-10 shrink-0 items-center justify-center rounded-xl bg-surface-100 text-surface-400">
              <Clapperboard class="h-5 w-5" />
            </div>
            <div class="min-w-0 flex-1">
              <p class="flex items-center gap-1.5 truncate text-sm font-medium text-surface-800">
                <span class="truncate">{{ version.fileName }}</span>
                <span
                  v-if="isDefaultVersion(version)"
                  class="shrink-0 rounded-md bg-primary-500/10 px-1.5 py-0.5 text-[10px] font-medium text-primary-600"
                >
                  默认
                </span>
              </p>
              <p
                v-if="versionChips(version)"
                class="mt-0.5 truncate text-xs text-surface-400"
              >
                {{ versionChips(version) }}
              </p>
            </div>
            <Play class="h-4 w-4 shrink-0 text-surface-800/40 group-hover:text-primary-500" />
          </button>
        </div>
      </div>
    </template>

    <TmdbMatchModal
      :open="matchOpen"
      media-type="movie"
      :initial-query="detail?.title ?? ''"
      @close="matchOpen = false"
      @select="handleMatched"
    />
  </div>
</template>
