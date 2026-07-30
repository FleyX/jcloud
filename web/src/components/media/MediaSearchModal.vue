<script setup lang="ts" generic="T">
/**
 * 媒体搜索弹窗：输入防抖 300ms 实时搜索，结果直接展示在弹窗内
 * - 滚动到底自动加载下一页
 * - 点击结果行触发 select，由父组件决定跳转/播放
 */
import { ref, watch, type Ref } from 'vue'
import { X, Search } from '@lucide/vue'
import type { MediaWallFetcher } from './useMediaWall'

const PAGE_SIZE = 20

interface Props {
  open: boolean
  placeholder?: string
  fetcher: MediaWallFetcher<T>
}

const props = withDefaults(defineProps<Props>(), {
  placeholder: '搜索文件名、剧名（电影名）、简介',
})
const emit = defineEmits<{
  close: []
  select: [item: T]
}>()

const input = ref('')
const results = ref([]) as Ref<T[]>
const loading = ref(false)
const loadingMore = ref(false)
const finished = ref(false)
const listEl = ref<HTMLElement | null>(null)

let pageNum = 1
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
  results.value = []
  loading.value = false
  loadingMore.value = false
  finished.value = false
  pageNum = 1
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
    const data = await props.fetcher({ pageNum: 1, pageSize: PAGE_SIZE, keyword })
    if (seq !== searchSeq) return
    results.value = data.records
    pageNum = 1
    finished.value = data.records.length >= Number(data.total)
  } finally {
    if (seq === searchSeq) loading.value = false
  }
}

async function loadMore() {
  if (loading.value || loadingMore.value || finished.value) return
  loadingMore.value = true
  const seq = searchSeq
  try {
    const data = await props.fetcher({ pageNum: pageNum + 1, pageSize: PAGE_SIZE, keyword: input.value.trim() })
    if (seq !== searchSeq) return
    if (data.records.length === 0) {
      finished.value = true
      return
    }
    pageNum += 1
    results.value.push(...data.records)
    finished.value = results.value.length >= Number(data.total)
  } finally {
    if (seq === searchSeq) loadingMore.value = false
  }
}

function onListScroll() {
  const el = listEl.value
  if (!el) return
  if (el.scrollTop + el.clientHeight >= el.scrollHeight - 60) loadMore()
}

function clear() {
  input.value = ''
  resetResults()
}

function select(item: T) {
  emit('select', item)
  emit('close')
}
</script>

<template>
  <div
    v-if="open"
    class="fixed inset-0 z-50 flex items-start justify-center bg-black/40 pt-32 backdrop-blur-sm"
    @click.self="emit('close')"
  >
    <div class="flex max-h-[70vh] w-full max-w-md flex-col rounded-3xl border border-surface-200 bg-white p-5 shadow-soft">
      <div class="flex items-center gap-3">
        <Search class="h-4 w-4 shrink-0 text-surface-400" />
        <input
          v-model="input"
          type="text"
          :placeholder="placeholder"
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

      <div
        v-if="input.trim()"
        ref="listEl"
        class="mt-3 min-h-0 flex-1 overflow-y-auto"
        @scroll="onListScroll"
      >
        <p
          v-if="loading"
          class="py-8 text-center text-sm text-surface-400"
        >
          搜索中…
        </p>
        <p
          v-else-if="results.length === 0"
          class="py-8 text-center text-sm text-surface-400"
        >
          未找到匹配的内容
        </p>
        <template v-else>
          <div
            v-for="(item, index) in results"
            :key="index"
            class="cursor-pointer rounded-xl px-2 py-2 transition-colors hover:bg-surface-100"
            @click="select(item)"
          >
            <slot
              name="row"
              :item="item"
            />
          </div>
          <p
            v-if="loadingMore"
            class="py-3 text-center text-xs text-surface-400"
          >
            加载中…
          </p>
          <p
            v-else-if="finished"
            class="py-3 text-center text-xs text-surface-300"
          >
            已加载全部
          </p>
        </template>
      </div>
      <p
        v-else
        class="mt-3 py-6 text-center text-xs text-surface-300"
      >
        输入关键词实时搜索
      </p>
    </div>
  </div>
</template>
