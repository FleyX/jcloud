<script setup lang="ts">
/**
 * 电视剧海报墙（PC/移动端共用）
 * - 点击卡片进入电视剧详情页
 */
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import type { MediaSeriesVo } from '@/types/media'
import { fetchMediaSeries } from '@/api/media'
import PosterCard from './PosterCard.vue'
import { cn } from '@/utils/cn'

interface Props {
  dense?: boolean
}

defineProps<Props>()

const router = useRouter()
const seriesList = ref<MediaSeriesVo[]>([])
const loading = ref(true)

onMounted(load)

async function load() {
  loading.value = true
  try {
    seriesList.value = await fetchMediaSeries()
  } finally {
    loading.value = false
  }
}

function openDetail(series: MediaSeriesVo) {
  router.push({ name: 'MediaSeriesDetail', params: { seriesName: series.seriesName } })
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
      暂无电视剧，请先在目录管理中添加电视目录
    </p>
    <div
      v-else
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
  </div>
</template>
