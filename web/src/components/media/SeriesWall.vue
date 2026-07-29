<script setup lang="ts">
/**
 * 电视剧海报墙（PC/移动端共用）
 * - 点击卡片进入电视剧详情页
 * - 分页加载（滚动到底自动加载）、搜索、排序
 */
import { useRouter } from 'vue-router'
import type { MediaSeriesVo } from '@/types/media'
import { fetchMediaSeries } from '@/api/media'
import PosterCard from './PosterCard.vue'
import MediaWallToolbar from './MediaWallToolbar.vue'
import MediaSearchModal from './MediaSearchModal.vue'
import { useMediaWall } from './useMediaWall'
import { cn } from '@/utils/cn'

interface Props {
  dense?: boolean
}

defineProps<Props>()

const router = useRouter()
const {
  items: seriesList,
  loading,
  loadingMore,
  finished,
  keyword,
  searchOpen,
  sortField,
  sortOrder,
  setSentinel,
  toggleSort,
  applySearch,
} = useMediaWall<MediaSeriesVo>('series', fetchMediaSeries)

function openDetail(series: MediaSeriesVo) {
  router.push({ name: 'MediaSeriesDetail', params: { seriesName: series.seriesName } })
}
</script>

<template>
  <div class="p-4 md:p-6">
    <MediaWallToolbar
      :keyword="keyword"
      :sort-field="sortField"
      :sort-order="sortOrder"
      @open-search="searchOpen = true"
      @clear-search="applySearch('')"
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
      {{ keyword ? '未找到匹配的电视剧' : '暂无电视剧，请先在目录管理中添加电视目录' }}
    </p>
    <template v-else>
      <div
        :class="cn('grid gap-4', dense ? 'grid-cols-2' : 'grid-cols-3 lg:grid-cols-5 xl:grid-cols-6')"
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
      :initial-keyword="keyword"
      placeholder="搜索剧名、简介"
      @close="searchOpen = false"
      @search="applySearch"
    />
  </div>
</template>
