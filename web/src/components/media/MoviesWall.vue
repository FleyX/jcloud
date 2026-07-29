<script setup lang="ts">
/**
 * 电影海报墙（PC/移动端共用）
 * - 点击卡片进入电影详情页
 * - 分页加载（滚动到底自动加载）、搜索、排序
 */
import { useRouter } from 'vue-router'
import type { MediaItemVo } from '@/types/media'
import { fetchMediaMovies } from '@/api/media'
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
  items: movies,
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
} = useMediaWall<MediaItemVo>('movies', fetchMediaMovies)

function openDetail(item: MediaItemVo) {
  router.push({ name: 'MediaMovieDetail', params: { id: item.id } })
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
      v-else-if="movies.length === 0"
      class="py-16 text-center text-sm text-surface-400"
    >
      {{ keyword ? '未找到匹配的电影' : '暂无电影，请先在目录管理中添加电影目录' }}
    </p>
    <template v-else>
      <div
        :class="cn('grid gap-4', dense ? 'grid-cols-2' : 'grid-cols-3 lg:grid-cols-5 xl:grid-cols-6')"
      >
        <PosterCard
          v-for="item in movies"
          :key="item.id"
          :title="item.title"
          :poster-url="item.posterUrl"
          :vote-average="item.voteAverage"
          :release-date="item.releaseDate"
          :progress-ms="item.progressMs"
          :duration-ms="item.durationMs"
          :unmatched="item.matchStatus === 'unmatched'"
          @play="openDetail(item)"
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
      @close="searchOpen = false"
      @search="applySearch"
    />
  </div>
</template>
