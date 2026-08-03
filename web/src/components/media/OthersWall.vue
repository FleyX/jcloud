<script setup lang="ts">
/**
 * 其他视频网格（PC/移动端共用）
 * - 不获取元数据，使用视频截图作为封面
 * - 分页加载（滚动到底自动加载）、排序
 * - 搜索在 MediaSearchModal 内展示结果，点击结果进入全屏播放页
 */
import { ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import type { MediaItemVo } from '@/types/media'
import { fetchMediaOthers } from '@/api/media'
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

const fetcher: MediaWallFetcher<MediaItemVo> = (query) =>
  fetchMediaOthers({ ...query, directoryId: props.directoryId })

const {
  items,
  loading,
  loadingMore,
  finished,
  sortField,
  sortOrder,
  setSentinel,
  reload,
  toggleSort,
} = useMediaWall<MediaItemVo>(props.directoryId ? `others:${props.directoryId}` : 'others', fetcher)

watch(
  () => props.directoryId,
  (id, prev) => {
    if (id !== prev) reload()
  },
)

const searchOpen = ref(false)

function handlePlay(item: MediaItemVo) {
  router.push({ name: 'MediaPlay', params: { id: item.id } })
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
      v-else-if="items.length === 0"
      class="py-16 text-center text-sm text-surface-400"
    >
      暂无视频，请先在目录管理中添加其他类型媒体库并扫描
    </p>
    <template v-else>
      <div
        :class="cn('grid gap-4', dense ? 'grid-cols-2' : 'grid-cols-3 sm:grid-cols-4 lg:grid-cols-7 xl:grid-cols-9')"
      >
        <PosterCard
          v-for="item in items"
          :key="item.id"
          :title="item.fileName"
          :poster-url="thumbUrl(item)"
          :release-date="formatDuration(item.durationMs)"
          :progress-ms="item.progressMs"
          :duration-ms="item.durationMs"
          owner-type="other"
          :owner-id="item.id"
          :favorited="item.favorited"
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

    <MediaSearchModal
      :open="searchOpen"
      placeholder="搜索文件名"
      :fetcher="fetcher"
      @close="searchOpen = false"
      @select="handlePlay"
    >
      <template #row="{ item }">
        <MediaSearchResultRow
          :title="item.fileName"
          :poster-url="thumbUrl(item)"
          :subtitle="formatDuration(item.durationMs)"
        />
      </template>
    </MediaSearchModal>
  </div>
</template>
