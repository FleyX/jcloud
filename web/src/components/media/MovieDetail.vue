<script setup lang="ts">
/**
 * 电影详情页（PC/移动端共用）
 * - 未识别条目展示文件信息简版详情，可修正匹配
 */
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import type { MediaItemDetailVo, TmdbSearchResultVo } from '@/types/media'
import { fetchItemDetail, refreshMetadata, updateMediaMatch } from '@/api/media'
import { formatSize } from '@/utils/fileDisplay'
import { useNotificationStore } from '@/store/notification'
import MediaDetailHero from './MediaDetailHero.vue'
import TmdbMatchModal from './TmdbMatchModal.vue'

const route = useRoute()
const router = useRouter()
const notificationStore = useNotificationStore()

const itemId = route.params.id as string
const detail = ref<MediaItemDetailVo | null>(null)
const loading = ref(true)
const matchOpen = ref(false)

onMounted(load)

async function load() {
  loading.value = true
  try {
    detail.value = await fetchItemDetail(itemId)
  } finally {
    loading.value = false
  }
}

const unmatched = computed(() => detail.value?.matchStatus === 'unmatched')

const fileInfoChips = computed(() => {
  const d = detail.value
  if (!d || !unmatched.value) return []
  const chips: string[] = []
  if (d.fileSize != null) chips.push(formatSize(d.fileSize))
  if (d.width && d.height) chips.push(`${d.width}×${d.height}`)
  if (d.videoCodec) chips.push(d.videoCodec.toUpperCase())
  if (d.audioCodec) chips.push(d.audioCodec.toUpperCase())
  return chips
})

function handlePlay(startMs: number) {
  router.push({ name: 'MediaPlay', params: { id: itemId }, query: startMs > 0 ? { startMs } : {} })
}

async function handleMatched(result: TmdbSearchResultVo) {
  await updateMediaMatch(itemId, result.tmdbId, 'movie')
  matchOpen.value = false
  await load()
}

async function handleRefresh() {
  if (!detail.value?.metadataId) return
  await refreshMetadata(detail.value.metadataId)
  notificationStore.success('元数据已刷新')
  await load()
}
</script>

<template>
  <div class="min-h-full bg-white">
    <p
      v-if="loading"
      class="py-24 text-center text-sm text-surface-400"
    >
      加载中…
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
        :duration-ms="detail.durationMs"
        :overview="detail.overview"
        :unmatched="unmatched"
        :continue-ms="detail.progressMs"
        :file-info-chips="fileInfoChips"
        :show-refresh="!!detail.metadataId"
        @play="handlePlay"
        @rematch="matchOpen = true"
        @refresh="handleRefresh"
      />
      <p class="mt-2 px-4 text-xs text-surface-400 md:px-10">
        {{ detail.fileName }}
      </p>
    </template>

    <TmdbMatchModal
      :open="matchOpen"
      media-type="movie"
      :initial-query="detail?.title ?? ''"
      @close="matchOpen = false"
      @select="handleMatched"
    />
  </div>
</template>
