<script setup lang="ts">
/**
 * 其他视频网格（PC/移动端共用）
 * - 不获取元数据，使用视频截图作为封面
 * - 分页加载（滚动到底自动加载）、搜索、排序
 */
import { ref } from 'vue'
import type { MediaItemVo } from '@/types/media'
import { fetchMediaOthers } from '@/api/media'
import PosterCard from './PosterCard.vue'
import MediaPlayerModal from './MediaPlayerModal.vue'
import MediaWallToolbar from './MediaWallToolbar.vue'
import MediaSearchModal from './MediaSearchModal.vue'
import { useMediaWall } from './useMediaWall'
import { cn } from '@/utils/cn'

interface Props {
  dense?: boolean
}

defineProps<Props>()

const {
  items,
  loading,
  loadingMore,
  finished,
  keyword,
  searchOpen,
  sortField,
  sortOrder,
  setSentinel,
  reload,
  toggleSort,
  applySearch,
} = useMediaWall<MediaItemVo>('others', fetchMediaOthers)

const playerOpen = ref(false)
const playingItem = ref<MediaItemVo | null>(null)

function handlePlay(item: MediaItemVo) {
  playingItem.value = item
  playerOpen.value = true
}

function handlePlayerClose() {
  playerOpen.value = false
  reload()
}

function thumbUrl(item: MediaItemVo): string {
  return `/jcloud/api/files/${item.fileNodeId}/preview?type=poster`
}

function formatDuration(ms: number | null): string | null {
  if (!ms) return null
  const totalMinutes = Math.floor(ms / 60000)
  const hours = Math.floor(totalMinutes / 60)
  const minutes = totalMinutes % 60
  return hours > 0 ? `${hours}小时${minutes}分` : `${minutes}分钟`
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
      v-else-if="items.length === 0"
      class="py-16 text-center text-sm text-surface-400"
    >
      {{ keyword ? '未找到匹配的视频' : '暂无视频，请先在目录管理中添加其他类型目录' }}
    </p>
    <template v-else>
      <div
        :class="cn('grid gap-4', dense ? 'grid-cols-2' : 'grid-cols-3 lg:grid-cols-5 xl:grid-cols-6')"
      >
        <PosterCard
          v-for="item in items"
          :key="item.id"
          :title="item.fileName"
          :poster-url="thumbUrl(item)"
          :release-date="formatDuration(item.durationMs)"
          :progress-ms="item.progressMs"
          :duration-ms="item.durationMs"
          @play="handlePlay(item)"
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

    <MediaPlayerModal
      :open="playerOpen"
      :item="playingItem"
      @close="handlePlayerClose"
    />

    <MediaSearchModal
      :open="searchOpen"
      :initial-keyword="keyword"
      placeholder="搜索文件名"
      @close="searchOpen = false"
      @search="applySearch"
    />
  </div>
</template>
