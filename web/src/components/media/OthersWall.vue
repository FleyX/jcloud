<script setup lang="ts">
/**
 * 其他视频网格（PC/移动端共用）
 * - 不获取元数据，使用视频截图作为封面
 */
import { onMounted, ref } from 'vue'
import type { MediaItemVo } from '@/types/media'
import { fetchMediaOthers } from '@/api/media'
import PosterCard from './PosterCard.vue'
import MediaPlayerModal from './MediaPlayerModal.vue'
import { cn } from '@/utils/cn'

interface Props {
  dense?: boolean
}

defineProps<Props>()

const items = ref<MediaItemVo[]>([])
const loading = ref(true)
const playerOpen = ref(false)
const playingItem = ref<MediaItemVo | null>(null)

onMounted(load)

async function load() {
  loading.value = true
  try {
    items.value = await fetchMediaOthers()
  } finally {
    loading.value = false
  }
}

function handlePlay(item: MediaItemVo) {
  playingItem.value = item
  playerOpen.value = true
}

function handlePlayerClose() {
  playerOpen.value = false
  load()
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
      暂无视频，请先在目录管理中添加其他类型目录
    </p>
    <div
      v-else
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

    <MediaPlayerModal
      :open="playerOpen"
      :item="playingItem"
      @close="handlePlayerClose"
    />
  </div>
</template>
