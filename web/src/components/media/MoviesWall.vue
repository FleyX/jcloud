<script setup lang="ts">
/**
 * 电影海报墙（PC/移动端共用）
 * - 点击卡片进入电影详情页
 * - 分页加载（滚动到底自动加载）、排序
 * - 搜索在 MediaSearchModal 内展示结果，点击结果进入详情页
 */
import { ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import type { MediaItemVo } from '@/types/media'
import { fetchMediaMovies } from '@/api/media'
import PosterCard from './PosterCard.vue'
import MediaSortDialog from './MediaSortDialog.vue'
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

const fetcher: MediaWallFetcher<MediaItemVo> = (query) =>
  fetchMediaMovies({ ...query, directoryId: props.directoryId, genre: props.genre })

const {
  items: movies,
  loading,
  loadingMore,
  finished,
  sortField,
  sortOrder,
  setSentinel,
  reload,
  setSort,
} = useMediaWall<MediaItemVo>(props.directoryId ? `movies:${props.directoryId}` : 'movies', fetcher)

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

function openDetail(item: MediaItemVo) {
  router.push({ name: 'MediaMovieDetail', params: { id: item.id } })
}

function resultSubtitle(item: MediaItemVo): string {
  const parts: string[] = []
  if (item.releaseDate) parts.push(item.releaseDate.slice(0, 4))
  if (item.voteAverage != null && item.voteAverage > 0) parts.push(`评分 ${item.voteAverage.toFixed(1)}`)
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
      v-else-if="movies.length === 0"
      class="py-16 text-center text-sm text-surface-400"
    >
      暂无电影，请先在目录管理中添加电影媒体库并扫描
    </p>
    <template v-else>
      <div
        :class="cn('grid gap-4', dense ? 'grid-cols-2' : 'grid-cols-3 sm:grid-cols-4 lg:grid-cols-7 xl:grid-cols-9')"
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
          owner-type="movie"
          :owner-id="item.id"
          :favorited="item.favorited"
          :watched="item.watched"
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
      placeholder="搜索电影名、简介"
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

    <MediaSortDialog
      :open="sortOpen"
      :fields="sortFields"
      :sort-field="sortField"
      :sort-order="sortOrder"
      @close="sortOpen = false"
      @confirm="setSort"
    />
  </div>
</template>
