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
import MediaWallToolbar from './MediaWallToolbar.vue'
import MediaSearchModal from './MediaSearchModal.vue'
import MediaSearchResultRow from './MediaSearchResultRow.vue'
import { useMediaWall, type MediaWallFetcher } from './useMediaWall'
import { cn } from '@/utils/cn'

interface Props {
  dense?: boolean
  /** 限定单个媒体库，为空表示跨库 */
  directoryId?: string
}

const props = defineProps<Props>()

const router = useRouter()

const fetcher: MediaWallFetcher<MediaSeriesVo> = (query) =>
  fetchMediaSeries({ ...query, directoryId: props.directoryId })

const {
  items: seriesList,
  loading,
  loadingMore,
  finished,
  sortField,
  sortOrder,
  setSentinel,
  reload,
  toggleSort,
} = useMediaWall<MediaSeriesVo>(props.directoryId ? `series:${props.directoryId}` : 'series', fetcher)

watch(
  () => props.directoryId,
  (id, prev) => {
    if (id !== prev) reload()
  },
)

const searchOpen = ref(false)

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
    <MediaWallToolbar
      :sort-field="sortField"
      :sort-order="sortOrder"
      @open-search="searchOpen = true"
      @sort="toggleSort"
    />

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
  </div>
</template>
