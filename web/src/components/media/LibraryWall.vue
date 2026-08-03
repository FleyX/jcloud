<script setup lang="ts">
/**
 * 库内海报墙（PC/移动端共用）
 * - 顶部影视菜单：{库类型名 / 我的收藏} Tab + 右侧库内搜索图标（复用墙组件内 MediaSearchModal，仅搜当前库）+ 配置图标
 * - Tab 状态经路由 query（?tab=favorites）承载，刷新可还原
 * - tab=favorites 渲染我的收藏（限定该库），否则按 mediaType 渲染对应海报墙
 */
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Search, Settings2 } from '@lucide/vue'
import type { MediaDirectoryVo, MediaType } from '@/types/media'
import { fetchMediaDirectories } from '@/api/media'
import MediaTopMenu from './MediaTopMenu.vue'
import MediaFavorites from './MediaFavorites.vue'
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
/** 当前渲染的墙组件（仅一个挂载），搜索入口经其 expose 的 openSearch 打开既有搜索弹窗 */
const wallRef = ref<{ openSearch?: () => void } | null>(null)

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

const menuTabs = computed(() => [
  { key: 'library', label: directory.value ? typeLabels[directory.value.mediaType] : '媒体库' },
  { key: 'favorites', label: '我的收藏' },
])

const activeTab = computed(() => (route.query.tab === 'favorites' ? 'favorites' : 'library'))

function selectTab(key: string) {
  if (key === activeTab.value) return
  const query = { ...route.query }
  if (key === 'favorites') {
    query.tab = 'favorites'
  } else {
    delete query.tab
  }
  router.replace({ query })
}

/** 库内搜索：调当前墙组件 openSearch（墙组件持有各自 MediaSearchModal，天然仅搜当前库） */
function openSearch() {
  wallRef.value?.openSearch?.()
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
    <MoviesWall
      v-else-if="directory.mediaType === 'movie'"
      ref="wallRef"
      :directory-id="directoryId"
      :dense="dense"
    />
    <SeriesWall
      v-else-if="directory.mediaType === 'tv'"
      ref="wallRef"
      :directory-id="directoryId"
      :dense="dense"
    />
    <OthersWall
      v-else
      ref="wallRef"
      :directory-id="directoryId"
      :dense="dense"
    />
  </div>
</template>
