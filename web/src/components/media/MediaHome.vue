<script setup lang="ts">
/**
 * 影视首页（PC/移动端共用，响应式）
 * - Jellyfin 风三段横向分区：我的媒体 / 继续观看 / 接下来
 * - 空分区整体隐藏；三个分区都无数据时显示空态引导
 */
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { Settings2, Film, Tv, Clapperboard, LibraryBig } from '@lucide/vue'
import type { Component } from 'vue'
import type { MediaDirectoryVo, MediaHomeVo, MediaItemVo, MediaType } from '@/types/media'
import { fetchMediaHome, withToken } from '@/api/media'

const router = useRouter()

const home = ref<MediaHomeVo | null>(null)
const loading = ref(true)

onMounted(async () => {
  try {
    home.value = await fetchMediaHome()
  } finally {
    loading.value = false
  }
})

const libraries = computed(() => home.value?.libraries ?? [])
const continueWatching = computed(() => home.value?.continueWatching ?? [])
const nextUp = computed(() => home.value?.nextUp ?? [])
const allEmpty = computed(
  () => libraries.value.length === 0 && continueWatching.value.length === 0 && nextUp.value.length === 0,
)

const typeLabels: Record<MediaType, string> = { movie: '电影', tv: '电视', other: '其他' }
const typeIcons: Record<MediaType, Component> = { movie: Film, tv: Tv, other: Clapperboard }

/** 剧集条目标题：剧名 SxxExx；其余用条目标题 */
function itemTitle(item: MediaItemVo): string {
  if (item.itemType === 'episode' && item.seriesName) {
    if (item.seasonNo != null && item.episodeNo != null) {
      const season = String(item.seasonNo).padStart(2, '0')
      const episode = String(item.episodeNo).padStart(2, '0')
      return `${item.seriesName} S${season}E${episode}`
    }
    return item.seriesName
  }
  return item.title
}

/** 条目封面：海报为空时回退到文件预览缩略图 */
function itemPoster(item: MediaItemVo): string {
  const url = item.posterUrl ?? `/jcloud/api/files/${item.fileNodeId}/preview?type=poster`
  return withToken(url)
}

function progressPercent(item: MediaItemVo): number {
  if (!item.progressMs || !item.durationMs || item.durationMs <= 0) return 0
  return Math.min(100, Math.round((item.progressMs / item.durationMs) * 100))
}

function libraryCover(directory: MediaDirectoryVo): string | null {
  return directory.coverPosterUrl ? withToken(directory.coverPosterUrl) : null
}

function openLibrary(directory: MediaDirectoryVo) {
  router.push({ name: 'MediaLibrary', params: { id: directory.id } })
}

function openDirectories() {
  router.push({ name: 'MediaDirectories' })
}

/** 继续观看：断点续播 */
function resume(item: MediaItemVo) {
  router.push({
    name: 'MediaPlay',
    params: { id: item.id },
    query: item.progressMs > 0 ? { startMs: item.progressMs } : {},
  })
}

/** 接下来：进入剧集详情对应季 */
function openNextUp(item: MediaItemVo) {
  if (!item.seriesId) return
  router.push({
    name: 'MediaSeriesDetail',
    params: { id: item.seriesId },
    query: item.seasonNo != null ? { season: item.seasonNo } : {},
  })
}
</script>

<template>
  <div class="p-4 md:p-6">
    <div class="mb-5 flex items-center justify-between">
      <h1 class="text-lg font-bold text-surface-900">
        影视
      </h1>
      <button
        class="flex items-center gap-1.5 rounded-xl border border-surface-200 bg-white px-3 py-1.5 text-sm font-medium text-surface-600 shadow-sm hover:bg-surface-50 hover:text-primary-600"
        title="目录管理"
        @click="openDirectories"
      >
        <Settings2 class="h-4 w-4" />
        目录管理
      </button>
    </div>

    <p
      v-if="loading"
      class="py-16 text-center text-sm text-surface-400"
    >
      加载中…
    </p>

    <!-- 空态引导 -->
    <div
      v-else-if="allEmpty"
      class="flex flex-col items-center py-20 text-center"
    >
      <div class="mb-4 flex h-16 w-16 items-center justify-center rounded-3xl bg-primary-50 text-primary-500">
        <LibraryBig class="h-8 w-8" />
      </div>
      <p class="text-sm font-medium text-surface-700">
        还没有媒体库
      </p>
      <p class="mt-1 text-xs text-surface-400">
        添加媒体库后，这里会展示你的电影、电视剧与观看进度
      </p>
      <button
        class="mt-5 rounded-xl bg-primary-600 px-5 py-2 text-sm font-semibold text-white hover:bg-primary-700"
        @click="openDirectories"
      >
        去目录管理添加
      </button>
    </div>

    <template v-else>
      <!-- 我的媒体 -->
      <section v-if="libraries.length > 0">
        <h2 class="mb-3 text-sm font-semibold text-surface-800">
          我的媒体
        </h2>
        <div class="flex gap-3 overflow-x-auto pb-2 md:gap-4">
          <div
            v-for="directory in libraries"
            :key="directory.id"
            class="group w-36 shrink-0 cursor-pointer md:w-44"
            @click="openLibrary(directory)"
          >
            <div class="relative aspect-[16/10] w-full overflow-hidden rounded-2xl bg-surface-100 shadow-soft transition-transform group-hover:scale-[1.02]">
              <img
                v-if="libraryCover(directory)"
                :src="libraryCover(directory)!"
                :alt="directory.name"
                loading="lazy"
                class="h-full w-full object-cover"
              >
              <div
                v-else
                class="flex h-full w-full items-center justify-center text-surface-300"
              >
                <component
                  :is="typeIcons[directory.mediaType]"
                  class="h-10 w-10"
                />
              </div>
              <div class="absolute inset-x-0 bottom-0 bg-gradient-to-t from-black/70 to-transparent px-3 pb-2 pt-6">
                <p class="truncate text-sm font-medium text-white">
                  {{ directory.name }}
                </p>
              </div>
            </div>
            <p class="mt-1.5 px-0.5 text-xs text-surface-400">
              {{ typeLabels[directory.mediaType] }} · {{ directory.itemCount }} 条目
            </p>
          </div>
        </div>
      </section>

      <!-- 继续观看 -->
      <section
        v-if="continueWatching.length > 0"
        class="mt-6"
      >
        <h2 class="mb-3 text-sm font-semibold text-surface-800">
          继续观看
        </h2>
        <div class="flex gap-3 overflow-x-auto pb-2 md:gap-4">
          <div
            v-for="item in continueWatching"
            :key="item.id"
            class="group w-52 shrink-0 cursor-pointer md:w-60"
            @click="resume(item)"
          >
            <div class="relative aspect-video w-full overflow-hidden rounded-2xl bg-surface-100 shadow-soft transition-transform group-hover:scale-[1.02]">
              <img
                :src="itemPoster(item)"
                :alt="itemTitle(item)"
                loading="lazy"
                class="h-full w-full object-cover"
              >
              <div
                v-if="progressPercent(item) > 0"
                class="absolute bottom-0 left-0 h-1 w-full bg-black/40"
              >
                <div
                  class="h-full bg-primary-500"
                  :style="{ width: `${progressPercent(item)}%` }"
                />
              </div>
            </div>
            <p class="mt-1.5 truncate px-0.5 text-sm font-medium text-surface-800">
              {{ itemTitle(item) }}
            </p>
          </div>
        </div>
      </section>

      <!-- 接下来 -->
      <section
        v-if="nextUp.length > 0"
        class="mt-6"
      >
        <h2 class="mb-3 text-sm font-semibold text-surface-800">
          接下来
        </h2>
        <div class="flex gap-3 overflow-x-auto pb-2 md:gap-4">
          <div
            v-for="item in nextUp"
            :key="item.id"
            class="group w-28 shrink-0 cursor-pointer md:w-36"
            @click="openNextUp(item)"
          >
            <div class="relative aspect-[2/3] w-full overflow-hidden rounded-2xl bg-surface-100 shadow-soft transition-transform group-hover:scale-[1.02]">
              <img
                :src="itemPoster(item)"
                :alt="itemTitle(item)"
                loading="lazy"
                class="h-full w-full object-cover"
              >
            </div>
            <p class="mt-1.5 truncate px-0.5 text-xs font-medium text-surface-800">
              {{ itemTitle(item) }}
            </p>
          </div>
        </div>
      </section>
    </template>
  </div>
</template>
