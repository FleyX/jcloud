<script setup lang="ts">
/**
 * 电影海报墙（PC/移动端共用）
 * - 点击卡片进入电影详情页
 */
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import type { MediaItemVo } from '@/types/media'
import { fetchMediaMovies } from '@/api/media'
import PosterCard from './PosterCard.vue'
import { cn } from '@/utils/cn'

interface Props {
  dense?: boolean
}

defineProps<Props>()

const router = useRouter()
const movies = ref<MediaItemVo[]>([])
const loading = ref(true)

onMounted(load)

async function load() {
  loading.value = true
  try {
    movies.value = await fetchMediaMovies()
  } finally {
    loading.value = false
  }
}

function openDetail(item: MediaItemVo) {
  router.push({ name: 'MediaMovieDetail', params: { id: item.id } })
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
      暂无电影，请先在目录管理中添加电影目录
    </p>
    <div
      v-else
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
  </div>
</template>
