<script setup lang="ts">
/**
 * TMDB 手动匹配弹窗
 * - 搜索 TMDB 条目并选择正确匹配
 */
import { ref, watch } from 'vue'
import { LoaderCircle, X, Search } from '@lucide/vue'
import type { TmdbSearchResultVo } from '@/types/media'
import { searchTmdb } from '@/api/media'

interface Props {
  open: boolean
  mediaType: 'movie' | 'tv'
  initialQuery?: string
}

const props = defineProps<Props>()
const emit = defineEmits<{
  close: []
  select: [result: TmdbSearchResultVo]
}>()

const query = ref('')
const year = ref('')
const searching = ref(false)
const searched = ref(false)
const results = ref<TmdbSearchResultVo[]>([])
/** 已点击选择、等待父组件处理中的条目 ID */
const selectingId = ref<number | null>(null)

watch(
  () => props.open,
  (open) => {
    if (open) {
      query.value = props.initialQuery ?? ''
      year.value = ''
      results.value = []
      searching.value = false
      searched.value = false
      selectingId.value = null
    }
  },
)

async function handleSearch() {
  if (!query.value.trim()) return
  searching.value = true
  try {
    const parsedYear = /^\d{4}$/.test(year.value.trim()) ? Number(year.value.trim()) : undefined
    results.value = await searchTmdb(props.mediaType, query.value.trim(), parsedYear)
    searched.value = true
  } finally {
    searching.value = false
  }
}

function handleSelect(result: TmdbSearchResultVo) {
  if (selectingId.value !== null) return
  selectingId.value = result.tmdbId
  emit('select', result)
}
</script>

<template>
  <div
    v-if="open"
    class="fixed inset-0 z-50 flex items-center justify-center bg-black/40 backdrop-blur-sm"
    @click.self="emit('close')"
  >
    <div class="flex max-h-[80vh] w-full max-w-lg flex-col rounded-3xl border border-surface-200 bg-white p-6 shadow-soft">
      <div class="mb-4 flex items-center gap-3">
        <h3 class="text-lg font-semibold text-surface-900">
          修正匹配
        </h3>
        <button
          class="ml-auto rounded-lg p-1 text-surface-400 hover:bg-surface-100"
          @click="emit('close')"
        >
          <X class="h-4 w-4" />
        </button>
      </div>

      <div class="mb-4 flex gap-2">
        <input
          v-model="query"
          type="text"
          placeholder="影片/剧集名称"
          class="flex-1 rounded-xl border border-surface-200 bg-surface-50 px-4 py-2 text-sm outline-none focus:border-primary-300 focus:bg-white"
          @keyup.enter="handleSearch"
        >
        <input
          v-model="year"
          type="text"
          placeholder="年份"
          class="w-20 rounded-xl border border-surface-200 bg-surface-50 px-3 py-2 text-sm outline-none focus:border-primary-300 focus:bg-white"
          @keyup.enter="handleSearch"
        >
        <button
          class="rounded-xl bg-primary-600 px-3 text-white hover:bg-primary-700"
          :disabled="searching"
          @click="handleSearch"
        >
          <Search class="h-4 w-4" />
        </button>
      </div>

      <div class="flex-1 overflow-y-auto">
        <p
          v-if="searching"
          class="py-8 text-center text-sm text-surface-400"
        >
          搜索中…
        </p>
        <p
          v-else-if="!searched"
          class="py-8 text-center text-sm text-surface-400"
        >
          输入名称后点击搜索
        </p>
        <p
          v-else-if="results.length === 0"
          class="py-8 text-center text-sm text-surface-400"
        >
          暂无结果
        </p>
        <div
          v-for="result in results"
          :key="result.tmdbId"
          class="mb-2 flex cursor-pointer items-center gap-3 rounded-2xl border border-surface-100 p-3 transition-colors hover:border-primary-200 hover:bg-primary-50/40"
          :class="{ 'pointer-events-none opacity-70': selectingId !== null }"
          @click="handleSelect(result)"
        >
          <img
            v-if="result.posterUrl"
            :src="result.posterUrl"
            :alt="result.title"
            class="h-16 w-11 rounded-lg object-cover"
          >
          <div class="min-w-0 flex-1">
            <p class="truncate text-sm font-medium text-surface-800">
              {{ result.title }}
              <span
                v-if="result.releaseDate"
                class="text-xs text-surface-400"
              >
                （{{ result.releaseDate.slice(0, 4) }}）
              </span>
            </p>
            <p class="line-clamp-2 text-xs text-surface-500">
              {{ result.overview }}
            </p>
          </div>
          <LoaderCircle
            v-if="selectingId === result.tmdbId"
            class="h-5 w-5 shrink-0 animate-spin text-primary-500"
          />
        </div>
      </div>
    </div>
  </div>
</template>
