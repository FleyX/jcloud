<script setup lang="ts">
/**
 * 影视首页（PC/移动端共用，响应式）
 * - 顶部影视菜单（首页/我的收藏 Tab + 配置图标），Tab 状态走路由 query，刷新可还原
 * - Jellyfin 风五段横向分区：我的媒体 / 继续观看 / 接下来 / 最新电影 / 最新剧集
 * - 空分区整体隐藏；五个分区都无数据时显示空态引导
 * - 最新卡片显示相对入库时间，页面停留期间每 60 秒刷新一次文本，不重新请求接口
 */
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Settings2, Search, Film, Tv, Clapperboard, LibraryBig } from '@lucide/vue'
import type { Component } from 'vue'
import type { MediaDirectoryVo, MediaHomeVo, MediaItemVo, MediaType } from '@/types/media'
import { fetchMediaHome, scanMediaDirectory, scrapeMediaDirectory } from '@/api/media'
import { useNotificationStore } from '@/store/notification'
import { formatMediaRelativeTime } from './format'
import MediaTopMenu from './MediaTopMenu.vue'
import MediaFavorites from './MediaFavorites.vue'
import GlobalSearchModal from './GlobalSearchModal.vue'
import LibraryCardMenu, { type LibraryMenuAction } from './LibraryCardMenu.vue'

const route = useRoute()
const router = useRouter()
const notificationStore = useNotificationStore()

const home = ref<MediaHomeVo | null>(null)
const loading = ref(true)

/** 图片加载失败的库/条目 ID 集合：加载失败视同无图走占位，重新加载首页数据时清空 */
const failedImageIds = ref(new Set<string>())

function markImageError(id: string) {
  failedImageIds.value.add(id)
}

function imageFailed(id: string): boolean {
  return failedImageIds.value.has(id)
}

onMounted(async () => {
  try {
    home.value = await fetchMediaHome()
    failedImageIds.value = new Set()
  } finally {
    loading.value = false
  }
})

/** 相对入库时间的计算基准：页面停留期间每 60 秒刷新一次，让「x分钟前」文本推进 */
const nowMs = ref(Date.now())
let relativeTimeTimer: ReturnType<typeof setInterval> | null = null

onMounted(() => {
  relativeTimeTimer = setInterval(() => {
    nowMs.value = Date.now()
  }, 60_000)
})

onBeforeUnmount(() => {
  if (relativeTimeTimer) clearInterval(relativeTimeTimer)
})

const libraries = computed(() => home.value?.libraries ?? [])
const continueWatching = computed(() => home.value?.continueWatching ?? [])
const nextUp = computed(() => home.value?.nextUp ?? [])
const latestMovies = computed(() => home.value?.latestMovies ?? [])
const latestSeries = computed(() => home.value?.latestSeries ?? [])
const allEmpty = computed(
  () =>
    libraries.value.length === 0 &&
    continueWatching.value.length === 0 &&
    nextUp.value.length === 0 &&
    latestMovies.value.length === 0 &&
    latestSeries.value.length === 0,
)

const typeLabels: Record<MediaType, string> = { movie: '电影', tv: '电视', other: '其他' }
const typeIcons: Record<MediaType, Component> = { movie: Film, tv: Tv, other: Clapperboard }

// ---------- 顶部影视菜单：Tab 状态经路由 query 承载 ----------

const menuTabs = [
  { key: 'home', label: '首页' },
  { key: 'favorites', label: '我的收藏' },
]

const activeTab = computed(() => (route.query.tab === 'favorites' ? 'favorites' : 'home'))

function selectTab(key: string) {
  if (key === activeTab.value) return
  // 首页 tab 不带 tab 参数，保持 URL 干净
  router.replace({ query: key === 'favorites' ? { tab: 'favorites' } : {} })
}

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
  return item.posterUrl ?? (item.fileNodeId ? `/jcloud/api/files/${item.fileNodeId}/preview?type=poster` : '')
}

/** 最新卡片封面：海报优先；仅在有代表文件时才回退文件预览缩略图，否则返回 null 显示占位 */
function latestPoster(item: MediaItemVo): string | null {
  if (item.posterUrl) return item.posterUrl
  return item.fileNodeId ? `/jcloud/api/files/${item.fileNodeId}/preview?type=poster` : null
}

function progressPercent(item: MediaItemVo): number {
  if (!item.progressMs || !item.durationMs || item.durationMs <= 0) return 0
  return Math.min(100, Math.round((item.progressMs / item.durationMs) * 100))
}

function libraryCover(directory: MediaDirectoryVo): string | null {
  return directory.coverPosterUrl ?? null
}

function openLibrary(directory: MediaDirectoryVo) {
  router.push({ name: 'MediaLibrary', params: { id: directory.id } })
}

/**
 * 库卡片菜单动作：复用目录扫描/削刮接口提交异步任务，
 * 成功即 toast，失败由统一请求层提示；不阻塞界面、不做任务状态轮询。
 */
function handleLibraryAction(directory: MediaDirectoryVo, kind: LibraryMenuAction) {
  const task =
    kind === 'scan'
      ? scanMediaDirectory(directory.id)
      : kind === 'refresh-missing'
        ? scrapeMediaDirectory(directory.id, false)
        : scrapeMediaDirectory(directory.id, true)
  const message =
    kind === 'scan'
      ? '已提交媒体库扫描'
      : kind === 'refresh-missing'
        ? '已提交缺失元数据刷新'
        : '已提交全量元数据刷新'
  task.then(() => notificationStore.success(message)).catch(() => {})
}

function openDirectories() {
  router.push({ name: 'MediaDirectories' })
}

/** 全局搜索弹窗开关 */
const searchOpen = ref(false)

/** 继续观看：断点续播 */
function resume(item: MediaItemVo) {
  router.push({
    name: 'MediaPlay',
    params: { id: item.id },
    query: item.progressMs != null && item.progressMs > 0 ? { startMs: item.progressMs } : {},
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

/** 最新电影：进入电影详情，不直接播放 */
function openLatestMovie(item: MediaItemVo) {
  router.push({ name: 'MediaMovieDetail', params: { id: item.id } })
}

/** 最新剧集：进入剧集详情，不直接播放 */
function openLatestSeries(item: MediaItemVo) {
  router.push({ name: 'MediaSeriesDetail', params: { id: item.id } })
}
</script>

<template>
  <div>
    <MediaTopMenu
      :tabs="menuTabs"
      :active="activeTab"
      @select="selectTab"
    >
      <button
        class="rounded-lg p-2 text-surface-400 transition-colors hover:bg-surface-100 hover:text-primary-600"
        title="全局搜索"
        @click="searchOpen = true"
      >
        <Search class="h-4 w-4" />
      </button>
      <button
        class="rounded-lg p-2 text-surface-400 transition-colors hover:bg-surface-100 hover:text-primary-600"
        title="目录管理"
        @click="openDirectories"
      >
        <Settings2 class="h-4 w-4" />
      </button>
    </MediaTopMenu>

    <GlobalSearchModal
      :open="searchOpen"
      @close="searchOpen = false"
    />

    <MediaFavorites v-if="activeTab === 'favorites'" />

    <div
      v-else
      class="p-4 md:p-6"
    >
      <h1 class="mb-5 text-lg font-bold text-surface-900">
        影视
      </h1>

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
                  v-if="libraryCover(directory) && !imageFailed(directory.id)"
                  :src="libraryCover(directory)!"
                  :alt="directory.name"
                  loading="lazy"
                  class="h-full w-full object-cover"
                  @error="markImageError(directory.id)"
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
                <!-- 库卡片菜单按钮：PC hover 显示、移动端常驻；@click.stop 防触发卡片跳转 -->
                <div
                  class="absolute bottom-2 right-2 z-10 transition-opacity md:opacity-0 md:group-hover:opacity-100"
                  @click.stop
                >
                  <LibraryCardMenu
                    :media-type="directory.mediaType"
                    @action="(kind) => handleLibraryAction(directory, kind)"
                  />
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
                  v-if="!imageFailed(item.id)"
                  :src="itemPoster(item)"
                  :alt="itemTitle(item)"
                  loading="lazy"
                  class="h-full w-full object-cover"
                  @error="markImageError(item.id)"
                >
                <div
                  v-else
                  class="flex h-full w-full items-center justify-center text-surface-300"
                >
                  <Film class="h-10 w-10" />
                </div>
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
                  v-if="!imageFailed(item.id)"
                  :src="itemPoster(item)"
                  :alt="itemTitle(item)"
                  loading="lazy"
                  class="h-full w-full object-cover"
                  @error="markImageError(item.id)"
                >
                <div
                  v-else
                  class="flex h-full w-full items-center justify-center text-surface-300"
                >
                  <Film class="h-10 w-10" />
                </div>
              </div>
              <p class="mt-1.5 truncate px-0.5 text-xs font-medium text-surface-800">
                {{ itemTitle(item) }}
              </p>
            </div>
          </div>
        </section>

        <!-- 最新电影 -->
        <section
          v-if="latestMovies.length > 0"
          class="mt-6"
        >
          <h2 class="mb-3 text-sm font-semibold text-surface-800">
            最新电影
          </h2>
          <div class="flex gap-3 overflow-x-auto pb-2 md:gap-4">
            <div
              v-for="item in latestMovies"
              :key="item.id"
              class="group w-28 shrink-0 cursor-pointer md:w-36"
              @click="openLatestMovie(item)"
            >
              <div class="relative aspect-[2/3] w-full overflow-hidden rounded-2xl bg-surface-100 shadow-soft transition-transform group-hover:scale-[1.02]">
                <img
                  v-if="latestPoster(item) && !imageFailed(item.id)"
                  :src="latestPoster(item)!"
                  :alt="itemTitle(item)"
                  loading="lazy"
                  class="h-full w-full object-cover"
                  @error="markImageError(item.id)"
                >
                <div
                  v-else
                  class="flex h-full w-full items-center justify-center text-surface-300"
                >
                  <Film class="h-10 w-10" />
                </div>
              </div>
              <p class="mt-1.5 truncate px-0.5 text-xs font-medium text-surface-800">
                {{ itemTitle(item) }}
              </p>
              <p class="px-0.5 text-xs text-surface-400">
                {{ formatMediaRelativeTime(item.addedTime, nowMs) }}
              </p>
            </div>
          </div>
        </section>

        <!-- 最新剧集 -->
        <section
          v-if="latestSeries.length > 0"
          class="mt-6"
        >
          <h2 class="mb-3 text-sm font-semibold text-surface-800">
            最新剧集
          </h2>
          <div class="flex gap-3 overflow-x-auto pb-2 md:gap-4">
            <div
              v-for="item in latestSeries"
              :key="item.id"
              class="group w-28 shrink-0 cursor-pointer md:w-36"
              @click="openLatestSeries(item)"
            >
              <div class="relative aspect-[2/3] w-full overflow-hidden rounded-2xl bg-surface-100 shadow-soft transition-transform group-hover:scale-[1.02]">
                <img
                  v-if="latestPoster(item) && !imageFailed(item.id)"
                  :src="latestPoster(item)!"
                  :alt="itemTitle(item)"
                  loading="lazy"
                  class="h-full w-full object-cover"
                  @error="markImageError(item.id)"
                >
                <div
                  v-else
                  class="flex h-full w-full items-center justify-center text-surface-300"
                >
                  <Tv class="h-10 w-10" />
                </div>
              </div>
              <p class="mt-1.5 truncate px-0.5 text-xs font-medium text-surface-800">
                {{ itemTitle(item) }}
              </p>
              <p class="px-0.5 text-xs text-surface-400">
                {{ formatMediaRelativeTime(item.addedTime, nowMs) }}
              </p>
            </div>
          </div>
        </section>
      </template>
    </div>
  </div>
</template>
