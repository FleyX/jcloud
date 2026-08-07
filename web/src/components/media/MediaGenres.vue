<script setup lang="ts">
/**
 * 类型页（PC/移动端共用）
 * - 该媒体库全部类型以卡片网格展示：代表海报封面，无海报时渐变底 + 类型名大字兜底
 * - 卡片底部渐变 overlay 显示类型名 + 条目数，点击回调父级进入按该类型筛选的海报墙
 */
import { ref, watch } from 'vue'
import type { MediaGenreVo } from '@/types/media'
import { fetchMediaGenres, withToken } from '@/api/media'

interface Props {
  directoryId: string
}

const props = defineProps<Props>()
const emit = defineEmits<{
  select: [genreName: string]
}>()

const genres = ref<MediaGenreVo[]>([])
const loading = ref(true)

/** 图片加载失败的类型名集合：加载失败视同无图走 v-else 渐变占位，切换目录时清空 */
const failedGenres = ref(new Set<string>())

function markGenreError(name: string) {
  failedGenres.value.add(name)
}

watch(
  () => props.directoryId,
  (id, prev) => {
    if (id !== prev) load(id)
  },
  { immediate: true },
)

async function load(id: string) {
  loading.value = true
  try {
    genres.value = await fetchMediaGenres(id)
    failedGenres.value = new Set()
  } finally {
    loading.value = false
  }
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
      v-else-if="genres.length === 0"
      class="py-16 text-center text-sm text-surface-400"
    >
      该媒体库还没有类型数据
    </p>
    <div
      v-else
      class="grid grid-cols-3 gap-4 sm:grid-cols-4 lg:grid-cols-7 xl:grid-cols-9"
    >
      <div
        v-for="genre in genres"
        :key="genre.name"
        class="group cursor-pointer"
        @click="emit('select', genre.name)"
      >
        <div class="relative aspect-[2/3] w-full overflow-hidden rounded-2xl bg-surface-100 shadow-soft transition-transform group-hover:scale-[1.02]">
          <img
            v-if="genre.posterUrl && !failedGenres.has(genre.name)"
            :src="withToken(genre.posterUrl)"
            :alt="genre.name"
            loading="lazy"
            class="h-full w-full object-cover"
            @error="markGenreError(genre.name)"
          >
          <div
            v-else
            class="flex h-full w-full items-center justify-center bg-gradient-to-br from-primary-400 to-primary-600 p-2"
          >
            <p class="line-clamp-3 text-center text-xl font-bold text-white">
              {{ genre.name }}
            </p>
          </div>
          <div class="absolute inset-x-0 bottom-0 bg-gradient-to-t from-black/70 to-transparent px-3 pb-2 pt-6">
            <p class="truncate text-sm font-medium text-white">
              {{ genre.name }}
            </p>
            <p class="text-xs text-white/70">
              {{ genre.itemCount }} 条目
            </p>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>
