/**
 * 搜索防抖 + 竞态防护 + 分页共享 composable
 * - 关键词变化后防抖 debounceMs（默认 300ms）再触发搜索，防抖窗口内多次输入只发一次请求
 * - searchSeq 竞态防护：每次搜索自增序号，慢响应到达时序号不匹配则丢弃
 * - 分页：loadMore 按 pageSize 累加 records，records 数量达到 total 后 finished
 * - 语义对齐 MediaSearchModal（先实现者）：reset 不递增 searchSeq，旧响应由输入门控隐藏
 */
import { ref, watch, type Ref } from 'vue'

export interface UseDebouncedSearchPage<T> {
  records: T[]
  total: number | string
}

export interface UseDebouncedSearchOptions<T> {
  /** 分页请求：keyword / page（从 1 开始）/ pageSize -> 当前页 records 与总数 */
  fetcher: (keyword: string, page: number, size: number) => Promise<UseDebouncedSearchPage<T>>
  /** 防抖毫秒数，默认 300 */
  debounceMs?: number
  /** 每页条数，默认 20 */
  pageSize?: number
  /** 共享关键词 ref（多个实例共用同一输入框时传入），缺省由实例内部创建 */
  keyword?: Ref<string>
  /** 是否允许触发搜索（弹窗关闭时忽略输入变化），缺省恒为 true */
  enabled?: () => boolean
}

export function useDebouncedSearch<T>(options: UseDebouncedSearchOptions<T>) {
  const { fetcher, debounceMs = 300, pageSize = 20, enabled = () => true } = options
  const keyword = options.keyword ?? ref('')

  const results = ref([]) as Ref<T[]>
  /** 最近一次响应中的总数（用于展示与加载更多按钮判定） */
  const total = ref(0)
  const loading = ref(false)
  const loadingMore = ref(false)
  const finished = ref(false)
  /** 是否已执行过有效搜索（非空关键词搜索完成），用于区分未搜索与无结果 */
  const searched = ref(false)

  let pageNum = 1
  let searchSeq = 0
  let timer: ReturnType<typeof setTimeout> | null = null

  watch(keyword, () => {
    if (!enabled()) return
    if (timer) clearTimeout(timer)
    timer = setTimeout(runSearch, debounceMs)
  })

  function reset() {
    results.value = []
    total.value = 0
    loading.value = false
    loadingMore.value = false
    finished.value = false
    searched.value = false
    pageNum = 1
  }

  /** 取消待触发的防抖搜索（弹窗关闭时调用，避免关闭后仍发起请求） */
  function clearTimer() {
    if (timer) {
      clearTimeout(timer)
      timer = null
    }
  }

  async function runSearch() {
    const kw = keyword.value.trim()
    searchSeq += 1
    if (!kw) {
      reset()
      return
    }
    loading.value = true
    const seq = searchSeq
    try {
      const data = await fetcher(kw, 1, pageSize)
      if (seq !== searchSeq) return
      results.value = data.records
      total.value = Number(data.total)
      pageNum = 1
      finished.value = data.records.length >= total.value
      searched.value = true
    } finally {
      if (seq === searchSeq) loading.value = false
    }
  }

  async function loadMore() {
    if (loading.value || loadingMore.value || finished.value) return
    loadingMore.value = true
    const seq = searchSeq
    try {
      const data = await fetcher(keyword.value.trim(), pageNum + 1, pageSize)
      if (seq !== searchSeq) return
      if (data.records.length === 0) {
        finished.value = true
        return
      }
      pageNum += 1
      results.value.push(...data.records)
      total.value = Number(data.total)
      finished.value = results.value.length >= total.value
    } finally {
      if (seq === searchSeq) loadingMore.value = false
    }
  }

  return {
    keyword,
    results,
    total,
    loading,
    loadingMore,
    finished,
    searched,
    runSearch,
    loadMore,
    reset,
    clearTimer,
  }
}
