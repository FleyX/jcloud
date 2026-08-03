<script setup lang="ts">
/**
 * 库内海报墙（PC/移动端共用）
 * - 顶部影视菜单：{库类型名 / 我的收藏 / 类型} Tab + 右侧库内搜索图标（复用墙组件内 MediaSearchModal，仅搜当前库）+ 配置图标
 * - Tab 状态经路由 query（?tab=favorites / ?tab=genres）承载，刷新可还原
 * - tab=favorites 渲染我的收藏（限定该库）；tab=genres 渲染类型页（电影/剧集库）
 * - ?genre= 筛选态归属库 Tab：渲染带类型筛选的海报墙，筛选条可清除
 */
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowUpDown, Search, Settings2, X } from '@lucide/vue'
import type { MediaDirectoryVo, MediaType } from '@/types/media'
import { fetchMediaDirectories } from '@/api/media'
import MediaTopMenu from './MediaTopMenu.vue'
import MediaFavorites from './MediaFavorites.vue'
import MediaGenres from './MediaGenres.vue'
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
/** 当前渲染的墙组件（仅一个挂载），搜索/排序入口经其 expose 的 openSearch/openSort 打开对应弹窗 */
const wallRef = ref<{ openSearch?: () => void; openSort?: () => void } | null>(null)

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

const typeLabels: Record<MediaType, string> = { movie: '电影', tv: '剧集', other: '其他' }

// ---------- 顶部影视菜单 ----------

const menuTabs = computed(() => {
  const tabs: Array<{ key: string; label: string }> = [
    { key: 'library', label: directory.value ? typeLabels[directory.value.mediaType] : '媒体库' },
    { key: 'favorites', label: '我的收藏' },
  ]
  // 类型 Tab 仅电影/剧集库渲染（其他库无类型概念）
  if (directory.value && directory.value.mediaType !== 'other') {
    tabs.push({ key: 'genres', label: '类型' })
  }
  return tabs
})

const activeTab = computed(() => {
  const tab = route.query.tab
  if (tab === 'favorites') return 'favorites'
  if (tab === 'genres') return 'genres'
  return 'library'
})

function selectTab(key: string) {
  if (key === activeTab.value) return
  const query = { ...route.query }
  // 类型 Tab 与筛选墙互斥：切换 Tab 时清掉 genre 筛选
  delete query.genre
  if (key === 'favorites') {
    query.tab = 'favorites'
  } else if (key === 'genres') {
    query.tab = 'genres'
  } else {
    delete query.tab
  }
  router.replace({ query })
}

/** 当前类型筛选（经路由 query 承载，刷新可还原） */
const genre = computed(() => (typeof route.query.genre === 'string' ? route.query.genre : undefined))

/** 点击类型卡片：进入按该类型筛选的海报墙（activeTab 回落 library，展示筛选 chip 与墙） */
function openGenre(name: string) {
  router.replace({ query: { genre: name } })
}

/** 清除类型筛选：去掉 genre query 回到主墙 */
function clearGenre() {
  const query = { ...route.query }
  delete query.genre
  router.replace({ query })
}

/** 库内搜索：调当前墙组件 openSearch（墙组件持有各自 MediaSearchModal，天然仅搜当前库） */
function openSearch() {
  wallRef.value?.openSearch?.()
}

/** 库内排序：调当前墙组件 openSort（排序弹窗由墙组件持有，作用于主墙） */
function openSort() {
  wallRef.value?.openSort?.()
}

function openDirectories() {
  router.push({ name: 'MediaDirectories' })
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
        title="搜索当前媒体库"
        @click="openSearch"
      >
        <Search class="h-4 w-4" />
      </button>
      <button
        class="rounded-lg p-2 text-surface-400 transition-colors hover:bg-surface-100 hover:text-primary-600"
        title="排序"
        @click="openSort"
      >
        <ArrowUpDown class="h-4 w-4" />
      </button>
      <button
        class="rounded-lg p-2 text-surface-400 transition-colors hover:bg-surface-100 hover:text-primary-600"
        title="目录管理"
        @click="openDirectories"
      >
        <Settings2 class="h-4 w-4" />
      </button>
    </MediaTopMenu>

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
    <MediaFavorites
      v-else-if="activeTab === 'favorites'"
      :directory-id="directoryId"
    />
    <MediaGenres
      v-else-if="activeTab === 'genres'"
      :directory-id="directoryId"
      @select="openGenre"
    />
    <template v-else>
      <!-- 类型筛选条：?genre= 存在且墙会应用该筛选（电影/剧集库）时展示，可一键清除 -->
      <div
        v-if="genre && (directory.mediaType === 'movie' || directory.mediaType === 'tv')"
        class="px-4 pt-4 md:px-6"
      >
        <span class="inline-flex items-center gap-1 rounded-full border border-primary-200 bg-primary-50 py-1 pl-3 pr-1 text-xs text-primary-700">
          类型：{{ genre }}
          <button
            class="flex h-5 w-5 items-center justify-center rounded-full transition-colors hover:bg-primary-100"
            title="清除类型筛选"
            @click="clearGenre"
          >
            <X class="h-3 w-3" />
          </button>
        </span>
      </div>
      <MoviesWall
        v-if="directory.mediaType === 'movie'"
        ref="wallRef"
        :directory-id="directoryId"
        :genre="genre"
        :dense="dense"
      />
      <SeriesWall
        v-else-if="directory.mediaType === 'tv'"
        ref="wallRef"
        :directory-id="directoryId"
        :genre="genre"
        :dense="dense"
      />
      <OthersWall
        v-else
        ref="wallRef"
        :directory-id="directoryId"
        :dense="dense"
      />
    </template>
  </div>
</template>
