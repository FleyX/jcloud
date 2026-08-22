<script setup lang="ts">
/**
 * 电视剧海报墙（PC/移动端共用）
 * - 点击卡片进入电视剧详情页
 * - 分页加载（滚动到底自动加载）、排序
 * - 搜索在 MediaSearchModal 内展示结果，点击结果进入详情页
 */
import { ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import type { MediaSeriesVo } from '@/types/media'
import { fetchMediaSeries } from '@/api/media'
import PosterCard from './PosterCard.vue'
import SortDialog from '@/components/SortDialog.vue'
import MediaSearchModal from './MediaSearchModal.vue'
import MediaSearchResultRow from './MediaSearchResultRow.vue'
import { useMediaWall, type MediaWallFetcher, type MediaWallSortField } from './useMediaWall'
import { cn } from '@/utils/cn'

interface Props {
  dense?: boolean
  /** 限定单个媒体库，为空表示跨库 */
  directoryId?: string
  /** 类型筛选（元数据 genres 拆分后包含该值），为空表示不过滤 */
  genre?: string
}

const props = defineProps<Props>()

const router = useRouter()

const fetcher: MediaWallFetcher<MediaSeriesVo> = (query) =>
  fetchMediaSeries({ ...query, directoryId: props.directoryId, genre: props.genre })

const {
  items: seriesList,
  loading,
  loadingMore,
  finished,
  sortField,
  sortOrder,
  setSentinel,
  reload,
  setSort,
} = useMediaWall<MediaSeriesVo>(props.directoryId ? `series:${props.directoryId}` : 'series', fetcher)

/** 排序字段选项：电影/剧集库四字段（工单 03） */
const sortFields: Array<{ value: MediaWallSortField; label: string }> = [
  { value: 'added', label: '添加时间' },
  { value: 'release', label: '发行时间' },
  { value: 'rating', label: '评分' },
  { value: 'title', label: '标题' },
]

watch(
  () => props.directoryId,
  (id, prev) => {
    if (id !== prev) reload()
  },
)

watch(
  () => props.genre,
  (genre, prev) => {
    if (genre !== prev) reload()
  },
)

const searchOpen = ref(false)
const sortOpen = ref(false)

/** 供库详情页顶栏搜索图标调用（复用本组件 MediaSearchModal，仅搜当前库） */
function openSearch() {
  searchOpen.value = true
}

/** 供库详情页顶栏排序图标调用 */
function openSort() {
  sortOpen.value = true
}

defineExpose({ openSearch, openSort })

function openDetail(series: MediaSeriesVo) {
  router.push({ name: 'MediaSeriesDetail', params: { id: series.id } })
}

function resultSubtitle(series: MediaSeriesVo): string {
  const parts: string[] = []
  if (series.releaseDate) parts.push(series.releaseDate.slice(0, 4))
  if (series.voteAverage != null && series.voteAverage > 0) parts.push(`评分 ${series.voteAverage.toFixed(1)}`)
  parts.push(`共 ${series.episodeCount} 集`)
  return parts.join(' · ')
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
    <p
      v-else-if="seriesList.length === 0"
      class="py-16 text-center text-sm text-surface-400"
    >
      暂无电视剧，请先在目录管理中添加电视媒体库并扫描
    </p>
    <template v-else>
      <div
        :class="cn('grid gap-4', dense ? 'grid-cols-2' : 'grid-cols-3 sm:grid-cols-4 lg:grid-cols-7 xl:grid-cols-9')"
      >
        <PosterCard
          v-for="series in seriesList"
          :key="series.seriesName"
          :title="series.title"
          :poster-url="series.posterUrl"
          :vote-average="series.voteAverage"
          :release-date="series.releaseDate"
          :unmatched="series.matchStatus === 'unmatched'"
          owner-type="series"
          :owner-id="series.id"
          :favorited="series.favorited"
          :watched="series.watched"
          @play="openDetail(series)"
        />
      </div>
      <div
        :ref="setSentinel"
        class="h-1"
      />
      <p
        v-if="loadingMore"
        class="py-4 text-center text-xs text-surface-400"
      >
        加载中…
      </p>
      <p
        v-else-if="finished"
        class="py-4 text-center text-xs text-surface-300"
      >
        已加载全部
      </p>
    </template>

    <MediaSearchModal
      :open="searchOpen"
      placeholder="搜索剧名、简介"
      :fetcher="fetcher"
      @close="searchOpen = false"
      @select="openDetail"
    >
      <template #row="{ item }">
        <MediaSearchResultRow
          :title="item.title"
          :poster-url="item.posterUrl"
          :subtitle="resultSubtitle(item)"
        />
      </template>
    </MediaSearchModal>

    <SortDialog
      :open="sortOpen"
      :fields="sortFields"
      :sort-field="sortField"
      :sort-order="sortOrder"
      @close="sortOpen = false"
      @confirm="setSort"
    />
  </div>
</template>
