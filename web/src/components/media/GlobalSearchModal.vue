<script setup lang="ts">
/**
 * 全局搜索弹窗：跨该用户全部媒体库搜索（文件名/剧名/元数据标题/原始标题/简介），按电影/剧集/其他分区展示
 * - 300ms 防抖输入实时搜索，竞态防护仿 MediaSearchModal（searchSeq）
 * - 各分区独立分页：首屏每组前 N 条 + 总数，分区底部「加载更多」追加
 * - 点击电影→电影详情、剧集→剧集详情、其他→直接播放，并关闭弹窗
 */
import { computed, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { X, Search, Film, Tv, Clapperboard } from '@lucide/vue'
import type { Component } from 'vue'
import type { MediaItemVo, MediaSeriesVo } from '@/types/media'
import { fetchMediaMovies, fetchMediaOthers, fetchMediaSeries, searchMedia } from '@/api/media'
import MediaSearchResultRow from './MediaSearchResultRow.vue'

/** 每组首屏条数（与后端 /media/search 默认 size 一致） */
const FIRST_PAGE_SIZE = 8

interface Props {
  open: boolean
}

const props = defineProps<Props>()
const emit = defineEmits<{
  close: []
}>()

const router = useRouter()

const input = ref('')
const loading = ref(false)
/** 是否已执行过有效搜索（用于区分未搜索与无结果） */
const searched = ref(false)

interface SearchSection<T> {
  items: T[]
  pageNum: number
  total: number
  loadingMore: boolean
}

const movies = ref<SearchSection<MediaItemVo>>({ items: [], pageNum: 0, total: 0, loadingMore: false })
const series = ref<SearchSection<MediaSeriesVo>>({ items: [], pageNum: 0, total: 0, loadingMore: false })
const others = ref<SearchSection<MediaItemVo>>({ items: [], pageNum: 0, total: 0, loadingMore: false })

type SectionKey = 'movies' | 'series' | 'others'

const sectionLabels: Record<SectionKey, string> = { movies: '电影', series: '剧集', others: '其他' }
const sectionIcons: Record<SectionKey, Component> = { movies: Film, series: Tv, others: Clapperboard }

let searchSeq = 0
let timer: ReturnType<typeof setTimeout> | null = null

watch(
  () => props.open,
  (open) => {
    if (open) {
      input.value = ''
      resetResults()
    } else if (timer) {
      clearTimeout(timer)
    }
  },
)

watch(input, () => {
  if (!props.open) return
  if (timer) clearTimeout(timer)
  timer = setTimeout(runSearch, 300)
})

function resetResults() {
  searched.value = false
  loading.value = false
  searchSeq += 1
  movies.value = { items: [], pageNum: 0, total: 0, loadingMore: false }
  series.value = { items: [], pageNum: 0, total: 0, loadingMore: false }
  others.value = { items: [], pageNum: 0, total: 0, loadingMore: false }
}

function clear() {
  input.value = ''
  resetResults()
}

async function runSearch() {
  const keyword = input.value.trim()
  searchSeq += 1
  if (!keyword) {
    resetResults()
    return
  }
  loading.value = true
  const seq = searchSeq
  try {
    const data = await searchMedia(keyword, FIRST_PAGE_SIZE)
    if (seq !== searchSeq) return
    movies.value = { items: data.movies.records, pageNum: 1, total: Number(data.movies.total), loadingMore: false }
    series.value = { items: data.series.records, pageNum: 1, total: Number(data.series.total), loadingMore: false }
    others.value = { items: data.others.records, pageNum: 1, total: Number(data.others.total), loadingMore: false }
    searched.value = true
  } finally {
    if (seq === searchSeq) loading.value = false
  }
}

async function loadMore(key: SectionKey) {
  const state = stateOf(key)
  if (state.loadingMore || state.items.length >= state.total) return
  state.loadingMore = true
  const seq = searchSeq
  try {
    const page = state.pageNum + 1
    const keyword = input.value.trim()
    const data =
      key === 'movies'
        ? await fetchMediaMovies({ pageNum: page, pageSize: FIRST_PAGE_SIZE, keyword })
        : key === 'series'
          ? await fetchMediaSeries({ pageNum: page, pageSize: FIRST_PAGE_SIZE, keyword })
          : await fetchMediaOthers({ pageNum: page, pageSize: FIRST_PAGE_SIZE, keyword })
    if (seq !== searchSeq) return
    state.pageNum = page
    state.items.push(...data.records)
    state.total = Number(data.total)
  } finally {
    if (seq === searchSeq) state.loadingMore = false
  }
}

function stateOf(key: SectionKey): SearchSection<MediaItemVo | MediaSeriesVo> {
  if (key === 'movies') return movies.value
  if (key === 'series') return series.value
  return others.value
}

/** 已初始化且非空的分区（total > 0 才显示） */
const visibleSections = computed<{ key: SectionKey; state: SearchSection<MediaItemVo | MediaSeriesVo> }[]>(() =>
  (['movies', 'series', 'others'] as const)
    .map((key) => ({ key, state: stateOf(key) }))
    .filter(({ state }) => state.total > 0),
)

const noResults = computed(() => searched.value && visibleSections.value.length === 0)

function openItem(key: SectionKey, item: MediaItemVo | MediaSeriesVo) {
  if (key === 'movies') {
    router.push({ name: 'MediaMovieDetail', params: { id: item.id } })
  } else if (key === 'series') {
    router.push({ name: 'MediaSeriesDetail', params: { id: item.id } })
  } else {
    router.push({ name: 'MediaPlay', params: { id: item.id } })
  }
  emit('close')
}

function sectionTitle(key: SectionKey, item: MediaItemVo | MediaSeriesVo): string {
  if (key === 'others') return (item as MediaItemVo).fileName || item.title
  return item.title
}

function sectionPoster(key: SectionKey, item: MediaItemVo | MediaSeriesVo): string | null {
  if (key === 'others') {
    const other = item as MediaItemVo
    return other.posterUrl ?? (other.fileNodeId ? `/jcloud/api/files/${other.fileNodeId}/preview?type=poster` : null)
  }
  return item.posterUrl
}

function sectionSubtitle(key: SectionKey, item: MediaItemVo | MediaSeriesVo): string | null {
  if (key === 'movies') return movieSubtitle(item as MediaItemVo)
  if (key === 'series') return seriesSubtitle(item as MediaSeriesVo)
  return formatDuration((item as MediaItemVo).durationMs)
}

/** 副标题仿 MoviesWall.resultSubtitle：年份 + 评分 */
function movieSubtitle(item: MediaItemVo): string {
  const parts: string[] = []
  if (item.releaseDate) parts.push(item.releaseDate.slice(0, 4))
  if (item.voteAverage != null && item.voteAverage > 0) parts.push(`评分 ${item.voteAverage.toFixed(1)}`)
  return parts.join(' · ')
}

function seriesSubtitle(item: MediaSeriesVo): string {
  const parts: string[] = []
  if (item.releaseDate) parts.push(item.releaseDate.slice(0, 4))
  if (item.voteAverage != null && item.voteAverage > 0) parts.push(`评分 ${item.voteAverage.toFixed(1)}`)
  parts.push(`共 ${item.episodeCount} 集`)
  return parts.join(' · ')
}

function formatDuration(ms: number | null): string | null {
  if (!ms) return null
  const totalMinutes = Math.floor(ms / 60000)
  const hours = Math.floor(totalMinutes / 60)
  const minutes = totalMinutes % 60
  return hours > 0 ? `${hours}小时${minutes}分` : `${minutes}分钟`
}
</script>

<template>
  <div
    v-if="open"
    class="fixed inset-0 z-50 flex items-start justify-center bg-black/40 pt-32 backdrop-blur-sm"
    @click.self="emit('close')"
  >
    <div class="flex max-h-[70vh] w-full max-w-lg flex-col rounded-3xl border border-surface-200 bg-white p-5 shadow-soft">
      <div class="flex items-center gap-3">
        <Search class="h-4 w-4 shrink-0 text-surface-400" />
        <input
          v-model="input"
          type="text"
          placeholder="跨全部媒体库搜索（文件名、剧名、简介）"
          class="flex-1 bg-transparent text-sm text-surface-800 outline-none placeholder:text-surface-300"
          autofocus
          @keydown.esc="emit('close')"
        >
        <button
          v-if="input"
          class="rounded-lg p-1 text-surface-400 hover:bg-surface-100"
          title="清空"
          @click="clear"
        >
          <X class="h-4 w-4" />
        </button>
        <button
          class="rounded-lg p-1 text-surface-400 hover:bg-surface-100"
          title="关闭"
          @click="emit('close')"
        >
          <X class="h-4 w-4" />
        </button>
      </div>

      <div class="mt-3 min-h-0 flex-1 overflow-y-auto">
        <p
          v-if="loading"
          class="py-8 text-center text-sm text-surface-400"
        >
          搜索中…
        </p>
        <p
          v-else-if="input.trim() && noResults"
          class="py-8 text-center text-sm text-surface-400"
        >
          未找到匹配的内容
        </p>
        <template v-else-if="input.trim()">
          <section
            v-for="{ key, state } in visibleSections"
            :key="key"
            class="mb-5"
          >
            <h2 class="mb-2 flex items-center gap-1.5 text-xs font-semibold text-surface-600">
              <component
                :is="sectionIcons[key]"
                class="h-3.5 w-3.5"
              />
              {{ sectionLabels[key] }}
              <span class="font-normal text-surface-300">{{ state.total }}</span>
            </h2>
            <div
              v-for="(item, index) in state.items"
              :key="`${key}-${item.id}-${index}`"
              class="cursor-pointer rounded-xl px-2 py-2 transition-colors hover:bg-surface-100"
              @click="openItem(key, item)"
            >
              <MediaSearchResultRow
                :title="sectionTitle(key, item)"
                :poster-url="sectionPoster(key, item)"
                :subtitle="sectionSubtitle(key, item)"
              />
            </div>
            <button
              v-if="state.items.length < state.total"
              class="mt-2 w-full rounded-xl border border-surface-200 bg-white py-1.5 text-xs text-surface-500 transition-colors hover:bg-surface-50 hover:text-primary-600"
              :disabled="state.loadingMore"
              @click="loadMore(key)"
            >
              {{ state.loadingMore ? '加载中…' : '加载更多' }}
            </button>
          </section>
        </template>
        <p
          v-else
          class="py-6 text-center text-xs text-surface-300"
        >
          输入关键词实时搜索
        </p>
      </div>
    </div>
  </div>
</template>
