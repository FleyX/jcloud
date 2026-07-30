<script setup lang="ts">
/**
 * 库内海报墙（PC/移动端共用）
 * - 按媒体库 mediaType 渲染对应的电影/剧集/其他海报墙，并限定该库
 * - 顶部带返回影视首页的导航
 */
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowLeft } from '@lucide/vue'
import type { MediaDirectoryVo, MediaType } from '@/types/media'
import { fetchMediaDirectories } from '@/api/media'
import MoviesWall from './MoviesWall.vue'
import SeriesWall from './SeriesWall.vue'
import OthersWall from './OthersWall.vue'

interface Props {
  dense?: boolean
}

defineProps<Props>()

const route = useRoute()
const router = useRouter()

const directoryId = computed(() => route.params.id as string)
const directory = ref<MediaDirectoryVo | null>(null)
const loading = ref(true)

watch(directoryId, load, { immediate: true })

async function load(id: string) {
  loading.value = true
  try {
    const directories = await fetchMediaDirectories()
    directory.value = directories.find((d) => d.id === id) ?? null
  } finally {
    loading.value = false
  }
}

const typeLabels: Record<MediaType, string> = { movie: '电影', tv: '电视', other: '其他' }

function backHome() {
  router.push({ name: 'MediaHome' })
}
</script>

<template>
  <div>
    <div class="flex items-center gap-2 px-4 pt-4 md:px-6 md:pt-6">
      <button
        class="flex h-8 w-8 items-center justify-center rounded-xl border border-surface-200 bg-white text-surface-500 shadow-sm hover:text-primary-600"
        title="返回影视首页"
        @click="backHome"
      >
        <ArrowLeft class="h-4 w-4" />
      </button>
      <h1 class="truncate text-lg font-bold text-surface-900">
        {{ directory?.name ?? '媒体库' }}
      </h1>
      <span
        v-if="directory"
        class="shrink-0 rounded-md bg-surface-100 px-1.5 py-0.5 text-xs text-surface-500"
      >
        {{ typeLabels[directory.mediaType] }}
      </span>
    </div>

    <p
      v-if="loading"
      class="py-16 text-center text-sm text-surface-400"
    >
      加载中…
    </p>
    <p
      v-else-if="!directory"
      class="py-16 text-center text-sm text-surface-400"
    >
      媒体库不存在或已被删除
    </p>
    <MoviesWall
      v-else-if="directory.mediaType === 'movie'"
      :directory-id="directoryId"
      :dense="dense"
    />
    <SeriesWall
      v-else-if="directory.mediaType === 'tv'"
      :directory-id="directoryId"
      :dense="dense"
    />
    <OthersWall
      v-else
      :directory-id="directoryId"
      :dense="dense"
    />
  </div>
</template>
