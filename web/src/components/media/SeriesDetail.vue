<script setup lang="ts">
/**
 * 电视剧详情页（PC/移动端共用）
 * - 默认季卡片网格；点击季卡片进入该季剧集列表态，顶部可返回季列表
 * - 页内状态用路由 query ?season={seasonId} 保持（兼容首页「接下来」传入的 seasonNo）
 * - 季剧集按季懒加载并缓存；头部播放按钮定位到「下一集待看」
 */
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowLeft, Check, Heart, LoaderCircle } from '@lucide/vue'
import type { MediaItemVo, MediaSeriesDetailVo, MediaSeriesSeasonVo, TmdbSearchResultVo } from '@/types/media'
import { fetchSeasonEpisodes, fetchSeriesDetail, refreshMetadata, toggleFavorite, updateMediaMatch, updateMediaWatched, type MediaRefreshMode } from '@/api/media'
import { useNotificationStore } from '@/store/notification'
import { formatDurationText } from './format'
import { useOptimisticToggle } from '@/composables/useOptimisticToggle'
import MediaDetailHero from './MediaDetailHero.vue'
import SeasonCard from './SeasonCard.vue'
import TmdbMatchModal from './TmdbMatchModal.vue'

const route = useRoute()
const router = useRouter()
const notificationStore = useNotificationStore()

const seriesId = route.params.id as string
const detail = ref<MediaSeriesDetailVo | null>(null)
const loading = ref(true)
const matchOpen = ref(false)

/** 当前处于剧集态的季（null = 季网格态） */
const activeSeason = ref<MediaSeriesSeasonVo | null>(null)
/** 各季剧集缓存：seasonId -> episodes */
const episodesCache = new Map<string, MediaItemVo[]>()
const episodes = ref<MediaItemVo[]>([])
const episodesLoading = ref(false)
const heroPlaying = ref(false)

/** 集剧照加载失败的 ID 集合：加载失败视同无图走 v-else 占位，刷新详情时清空（季海报错误由 SeasonCard 内部自管） */
const failedEpisodePosters = ref(new Set<string>())

function markEpisodePosterError(episodeId: string) {
  failedEpisodePosters.value.add(episodeId)
}

onMounted(load)

async function load() {
  loading.value = true
  try {
    detail.value = await fetchSeriesDetail(seriesId)
    failedEpisodePosters.value = new Set()
    episodesCache.clear()
    syncSeasonFromQuery()
  } finally {
    loading.value = false
  }
}

const unmatched = computed(() => detail.value?.matchStatus === 'unmatched')

/**
 * 解析 ?season= 查询：优先按 seasonId 精确匹配，
 * 兼容首页「接下来」跳转传入的 seasonNo（详情页会规范化为 seasonId）
 */
function resolveSeason(query: unknown): MediaSeriesSeasonVo | null {
  const raw = Array.isArray(query) ? query[0] : query
  if (raw == null || raw === '') return null
  const seasons = detail.value?.seasons ?? []
  const byId = seasons.find((season) => season.seasonId === raw)
  if (byId) return byId
  const seasonNo = Number(raw)
  if (Number.isInteger(seasonNo)) {
    return seasons.find((season) => season.seasonNo === seasonNo) ?? null
  }
  return null
}

/** 路由 query 变化（刷新/回退/外部跳转）时同步季状态 */
watch(() => route.query.season, syncSeasonFromQuery)

function syncSeasonFromQuery() {
  if (!detail.value) return
  const season = resolveSeason(route.query.season)
  activeSeason.value = season
  if (season) {
    // 规范化为 seasonId，刷新后状态稳定
    if (route.query.season !== season.seasonId) {
      router.replace({ query: { ...route.query, season: season.seasonId } })
    }
    void loadEpisodes(season)
  } else {
    episodes.value = []
  }
}

async function loadEpisodes(season: MediaSeriesSeasonVo, force = false) {
  if (!force && episodesCache.has(season.seasonId)) {
    episodes.value = episodesCache.get(season.seasonId) ?? []
    return
  }
  episodesLoading.value = true
  try {
    const list = await ensureEpisodes(season)
    if (activeSeason.value?.seasonId === season.seasonId) {
      episodes.value = list
    }
  } finally {
    episodesLoading.value = false
  }
}

async function ensureEpisodes(season: MediaSeriesSeasonVo): Promise<MediaItemVo[]> {
  const cached = episodesCache.get(season.seasonId)
  if (cached) return cached
  const list = await fetchSeasonEpisodes(seriesId, season.seasonId)
  episodesCache.set(season.seasonId, list)
  return list
}

function openSeason(season: MediaSeriesSeasonVo) {
  router.replace({ query: { ...route.query, season: season.seasonId } })
}

function backToSeasons() {
  const query = { ...route.query }
  delete query.season
  router.replace({ query })
}

function seasonTitle(season: MediaSeriesSeasonVo): string {
  return season.seasonNo != null ? `第 ${season.seasonNo} 季` : '未知季'
}

/**
 * 下一集待看：按标记为准，第一集未观看的集（watched=false），全部看完则为第一集。
 * （不再按完播阈值自算，阈值计算收敛到后端进度上报自动置位。）
 */
function findNextUp(list: MediaItemVo[]): MediaItemVo | null {
  if (list.length === 0) return null
  return list.find((episode) => !episode.watched) ?? list[0]
}

/** 季剧集态下的下一集待看（驱动 Hero 续播显示） */
const seasonNextUp = computed(() => (activeSeason.value ? findNextUp(episodes.value) : null))
const continueMs = computed(() => seasonNextUp.value?.progressMs ?? 0)

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

/**
 * Hero 播放：季剧集态播放当前季 nextUp；
 * 季网格态取首个有观看进度的季（无则第一季），懒加载其剧集后定位 nextUp
 */
async function handleHeroPlay(startMs: number) {
  const seasons = detail.value?.seasons ?? []
  if (seasons.length === 0 || heroPlaying.value) return
  heroPlaying.value = true
  try {
    let target: MediaItemVo | null
    if (activeSeason.value) {
      target = findNextUp(episodes.value)
    } else {
      const season = seasons.find((s) => s.hasProgress) ?? seasons[0]
      target = findNextUp(await ensureEpisodes(season))
    }
    if (target) {
      playEpisode(target, startMs)
    }
  } finally {
    heroPlaying.value = false
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
  await updateMediaMatch(seriesId, result.tmdbId, 'tv')
  matchOpen.value = false
  await load()
}

async function handleRefresh(mode: MediaRefreshMode) {
  if (!detail.value?.metadataId) return
  await refreshMetadata(detail.value.metadataId, mode)
  notificationStore.success(mode === 'force' ? '元数据已强制刷新' : '元数据已刷新')
  await load()
}

/** 收藏/取消收藏（剧/季/集）：本地先翻转，成功后以服务端结果为准，失败回滚（异常提示由统一请求层处理） */
async function toggleSeriesFavorite() {
  if (!detail.value) return
  const previous = detail.value.favorited
  detail.value.favorited = !previous
  try {
    detail.value.favorited = await toggleFavorite('series', seriesId)
  } catch {
    detail.value.favorited = previous
  }
}

async function toggleEpisodeFavorite(episode: MediaItemVo) {
  const previous = episode.favorited
  episode.favorited = !previous
  try {
    episode.favorited = await toggleFavorite('episode', episode.id)
  } catch {
    episode.favorited = previous
  }
}

/** 静默重拉详情：季卡片（SeasonCard）收藏/已观看切换成功后调用，保持父级聚合一致 */
async function refreshDetail() {
  if (!detail.value) return
  detail.value = await fetchSeriesDetail(seriesId)
}

/** 标记/取消整剧已观看：成功后重拉详情保持季卡片聚合一致（后端联动），失败回滚 */
const toggleSeriesWatched = useOptimisticToggle({
  isWatched: () => !!detail.value?.watched,
  setWatched: (watched) => {
    if (detail.value) detail.value.watched = watched
  },
  toggle: (watched) => updateMediaWatched(seriesId, watched),
  onSuccess: refreshDetail,
}).toggle

/** 集行已观看切换：标记时本地清零进度，成功后重拉详情保持季/整剧聚合一致，失败回滚 */
async function toggleEpisodeWatched(episode: MediaItemVo) {
  await useOptimisticToggle({
    isWatched: () => !!episode.watched,
    setWatched: (watched) => {
      episode.watched = watched
    },
    progress: {
      get: () => episode.progressMs ?? null,
      set: (value) => {
        episode.progressMs = value ?? null
      },
    },
    toggle: (watched) => updateMediaWatched(episode.id, watched),
    onSuccess: refreshDetail,
  }).toggle()
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
        :continue-ms="continueMs"
        :show-refresh="!!detail.metadataId"
        :favorited="detail.favorited"
        :watched="detail.watched"
        @play="handleHeroPlay"
        @rematch="matchOpen = true"
        @refresh="handleRefresh"
        @toggle-favorite="toggleSeriesFavorite"
        @toggle-watched="toggleSeriesWatched"
      />

      <div class="mt-6 px-4 pb-8 md:px-10">
        <!-- 季卡片网格 -->
        <template v-if="!activeSeason">
          <h2 class="mb-3 text-base font-semibold text-surface-900">
            季（{{ detail.seasons.length }}）
          </h2>
          <div class="grid grid-cols-3 gap-3 sm:grid-cols-4 md:gap-4 lg:grid-cols-7 xl:grid-cols-9">
            <SeasonCard
              v-for="season in detail.seasons"
              :key="season.seasonId"
              :season="season"
              @open="openSeason(season)"
              @updated="refreshDetail"
            />
          </div>
        </template>

        <!-- 季剧集列表 -->
        <template v-else>
          <div class="mb-3 flex items-center gap-2">
            <button
              class="flex items-center gap-1 rounded-xl border border-surface-200 bg-white px-3 py-1.5 text-sm font-medium text-surface-600 shadow-sm hover:bg-surface-50 hover:text-primary-600"
              @click="backToSeasons"
            >
              <ArrowLeft class="h-4 w-4" />
              返回季列表
            </button>
            <h2 class="text-base font-semibold text-surface-900">
              {{ seasonTitle(activeSeason) }}（{{ activeSeason.episodeCount }} 集）
            </h2>
          </div>

          <p
            v-if="episodesLoading"
            class="flex items-center justify-center gap-2 py-12 text-sm text-surface-400"
          >
            <LoaderCircle class="h-4 w-4 animate-spin" />加载中…
          </p>
          <div
            v-else
            class="divide-y divide-surface-100 rounded-2xl border border-surface-100"
          >
            <div
              v-for="episode in episodes"
              :key="episode.id"
              class="flex w-full cursor-pointer items-center gap-3 px-3 py-3 text-left transition-colors hover:bg-surface-50 md:gap-4 md:px-4"
              @click="playEpisode(episode)"
            >
              <div class="relative aspect-video w-24 shrink-0 overflow-hidden rounded-xl bg-surface-100 md:w-32">
                <img
                  v-if="episode.posterUrl && !failedEpisodePosters.has(episode.id)"
                  :src="episode.posterUrl"
                  :alt="episode.title"
                  loading="lazy"
                  class="h-full w-full object-cover"
                  @error="markEpisodePosterError(episode.id)"
                >
                <span
                  v-else
                  class="flex h-full w-full items-center justify-center text-xs font-semibold text-surface-500"
                >
                  {{ episodeLabel(episode) }}
                </span>
                <span
                  v-if="episode.posterUrl && !failedEpisodePosters.has(episode.id)"
                  class="absolute bottom-1 left-1 rounded bg-black/60 px-1 py-0.5 text-[10px] font-semibold text-white"
                >
                  {{ episodeLabel(episode) }}
                </span>
                <div
                  v-if="progressPercent(episode) > 0"
                  class="absolute bottom-0 left-0 h-0.5 w-full bg-black/40"
                >
                  <div
                    class="h-full bg-primary-500"
                    :style="{ width: `${progressPercent(episode)}%` }"
                  />
                </div>
              </div>
              <div class="min-w-0 flex-1">
                <p class="truncate text-sm font-medium text-surface-800">
                  {{ episode.title }}
                </p>
                <span
                  v-if="formatDurationText(episode.durationMs)"
                  class="mt-1 inline-block text-xs text-surface-400"
                >
                  {{ formatDurationText(episode.durationMs) }}
                </span>
              </div>
              <!-- 集行收藏心形按钮 -->
              <button
                class="shrink-0 rounded-full p-2 transition-colors hover:bg-surface-100"
                :class="episode.favorited ? 'text-rose-500' : 'text-surface-300'"
                :title="episode.favorited ? '取消收藏' : '收藏'"
                @click.stop="toggleEpisodeFavorite(episode)"
              >
                <Heart
                  class="h-4 w-4"
                  :class="episode.favorited && 'fill-rose-500'"
                />
              </button>
              <!-- 集行已观看切换按钮：已观看实心高亮，未观看淡色 -->
              <button
                class="shrink-0 rounded-full p-2 transition-colors hover:bg-surface-100"
                :class="episode.watched ? 'text-emerald-500' : 'text-surface-300'"
                :title="episode.watched ? '标记未观看' : '标记已观看'"
                @click.stop="toggleEpisodeWatched(episode)"
              >
                <Check
                  class="h-4 w-4"
                  :class="episode.watched && 'fill-emerald-500'"
                />
              </button>
            </div>
          </div>
        </template>
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
