<script setup lang="ts">
/**
 * 我的收藏页（PC/移动端共用）
 * - 五分区（电影/剧集/季/集/其他），空分区隐藏，每区独立分页加载（pageSize 24）
 * - 点击导航：电影→电影详情；剧集→剧集详情；季/集→剧集详情定位对应季；其他→直接播放
 * - 卡片心形就地取消收藏成功后该卡片从分区移除
 */
import { computed, onMounted, reactive } from 'vue'
import { useRouter } from 'vue-router'
import type { MediaFavoriteOwnerType, MediaFavoriteVo } from '@/types/media'
import { fetchMediaFavorites } from '@/api/media'
import { formatDuration } from '@/utils/format'
import PosterCard from './PosterCard.vue'

interface Props {
  /** 限定单个媒体库，为空表示全部收藏 */
  directoryId?: string
}

const props = defineProps<Props>()

const router = useRouter()

const PAGE_SIZE = 24

interface FavoriteSection {
  items: MediaFavoriteVo[]
  pageNum: number
  total: number
  loading: boolean
  loadingMore: boolean
  initialized: boolean
}

const sections = reactive<Record<MediaFavoriteOwnerType, FavoriteSection>>({
  movie: { items: [], pageNum: 0, total: 0, loading: false, loadingMore: false, initialized: false },
  series: { items: [], pageNum: 0, total: 0, loading: false, loadingMore: false, initialized: false },
  season: { items: [], pageNum: 0, total: 0, loading: false, loadingMore: false, initialized: false },
  episode: { items: [], pageNum: 0, total: 0, loading: false, loadingMore: false, initialized: false },
  other: { items: [], pageNum: 0, total: 0, loading: false, loadingMore: false, initialized: false },
})

const sectionLabels: Record<MediaFavoriteOwnerType, string> = {
  movie: '电影',
  series: '剧集',
  season: '季',
  episode: '集',
  other: '其他',
}

const sectionKeys = Object.keys(sections) as MediaFavoriteOwnerType[]

/** 已初始化且非空的分区才渲染 */
const visibleSections = computed(() =>
  sectionKeys.filter((key) => sections[key].initialized && sections[key].total > 0),
)
const loading = computed(() => sectionKeys.some((key) => sections[key].loading))
const allEmpty = computed(() =>
  sectionKeys.every((key) => sections[key].initialized) && visibleSections.value.length === 0,
)

onMounted(() => {
  // 首屏五区各发一页请求（并行）
  sectionKeys.forEach((key) => loadSection(key))
})

async function loadSection(type: MediaFavoriteOwnerType) {
  const section = sections[type]
  if (section.initialized) return
  section.loading = true
  try {
    const data = await fetchMediaFavorites({
      ownerType: type,
      directoryId: props.directoryId,
      pageNum: 1,
      pageSize: PAGE_SIZE,
    })
    section.items = data.records
    section.total = Number(data.total)
    section.pageNum = 1
    section.initialized = true
  } finally {
    section.loading = false
  }
}

async function loadMore(type: MediaFavoriteOwnerType) {
  const section = sections[type]
  if (section.loadingMore || section.items.length >= section.total) return
  section.loadingMore = true
  try {
    const data = await fetchMediaFavorites({
      ownerType: type,
      directoryId: props.directoryId,
      pageNum: section.pageNum + 1,
      pageSize: PAGE_SIZE,
    })
    section.pageNum += 1
    section.items.push(...data.records)
    section.total = Number(data.total)
  } finally {
    section.loadingMore = false
  }
}

/** 心形就地取消收藏成功后移除该卡片并扣减总数 */
function removeItem(type: MediaFavoriteOwnerType, item: MediaFavoriteVo) {
  const section = sections[type]
  section.items = section.items.filter((it) => it.ownerId !== item.ownerId)
  section.total = Math.max(0, section.total - 1)
}

function cardTitle(type: MediaFavoriteOwnerType, item: MediaFavoriteVo): string {
  if (type === 'season') return seasonTitle(item)
  if (type === 'episode') return episodeTitle(item)
  return item.title ?? item.fileName ?? ''
}

function seasonTitle(item: MediaFavoriteVo): string {
  if (item.seasonNo == null) return item.seriesName ?? '未知季'
  return `${item.seriesName ?? ''} 第${item.seasonNo}季`
}

function episodeTitle(item: MediaFavoriteVo): string {
  if (item.seasonNo != null && item.episodeNo != null) {
    const season = String(item.seasonNo).padStart(2, '0')
    const episode = String(item.episodeNo).padStart(2, '0')
    return `${item.seriesName ?? ''} S${season}E${episode}`
  }
  return item.seriesName ?? '未知集'
}

function cardPoster(type: MediaFavoriteOwnerType, item: MediaFavoriteVo): string | null {
  // other 后端不返回海报，回退文件预览缩略图（与 OthersWall 一致）
  if (type === 'other') {
    return item.posterUrl ?? (item.fileNodeId ? `/jcloud/api/files/${item.fileNodeId}/preview?type=poster` : null)
  }
  return item.posterUrl
}

function openItem(type: MediaFavoriteOwnerType, item: MediaFavoriteVo) {
  if (type === 'movie') {
    router.push({ name: 'MediaMovieDetail', params: { id: item.ownerId } })
    return
  }
  if (type === 'series') {
    router.push({ name: 'MediaSeriesDetail', params: { id: item.ownerId } })
    return
  }
  if (type === 'season' || type === 'episode') {
    if (!item.seriesId) return
    router.push({
      name: 'MediaSeriesDetail',
      params: { id: item.seriesId },
      query: item.seasonNo != null ? { season: item.seasonNo } : {},
    })
    return
  }
  router.push({ name: 'MediaPlay', params: { id: item.ownerId } })
}
</script>

<template>
  <div class="p-4 md:p-6">
    <p
      v-if="loading"
      class="py-16 text-center text-sm text-surface-400"
    >
      加载中…
    </p>
    <div
      v-else-if="allEmpty"
      class="flex flex-col items-center py-20 text-center"
    >
      <p class="text-sm font-medium text-surface-700">
        还没有收藏
      </p>
      <p class="mt-1 text-xs text-surface-400">
        在海报墙上点击卡片右上角心形，收藏你喜欢的影视
      </p>
    </div>
    <template v-else>
      <section
        v-for="type in visibleSections"
        :key="type"
        class="mb-8"
      >
        <h2 class="mb-3 text-sm font-semibold text-surface-800">
          {{ sectionLabels[type] }}
        </h2>
        <div class="grid grid-cols-3 gap-4 sm:grid-cols-4 lg:grid-cols-7 xl:grid-cols-9">
          <PosterCard
            v-for="item in sections[type].items"
            :key="`${type}-${item.ownerId}`"
            :title="cardTitle(type, item)"
            :poster-url="cardPoster(type, item)"
            :vote-average="item.voteAverage"
            :release-date="type === 'other' ? formatDuration(item.durationMs) : item.releaseDate"
            :unmatched="(type === 'movie' || type === 'series') && item.matchStatus === 'unmatched'"
            :owner-type="type"
            :owner-id="item.ownerId"
            :favorited="item.favorited"
            :watched="item.watched"
            @play="openItem(type, item)"
            @unfavorite="removeItem(type, item)"
          />
        </div>
        <button
          v-if="sections[type].items.length < sections[type].total"
          class="mt-4 w-full rounded-xl border border-surface-200 bg-white py-2 text-sm text-surface-500 transition-colors hover:bg-surface-50 hover:text-primary-600"
          :disabled="sections[type].loadingMore"
          @click="loadMore(type)"
        >
          {{ sections[type].loadingMore ? '加载中…' : '加载更多' }}
        </button>
      </section>
    </template>
  </div>
</template>
