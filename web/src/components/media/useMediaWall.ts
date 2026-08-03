/**
 * 媒体海报墙分页/排序组合式函数
 * - 滚动到底自动加载下一页（IntersectionObserver 哨兵）
 * - 排序选择记忆到 localStorage（key 与值结构不变，字段枚举随工单 03 扩展）
 * - 搜索由 MediaSearchModal/GlobalSearchModal 独立完成，不再作用于海报墙
 */
import { onBeforeUnmount, onMounted, ref, watch, type Ref } from 'vue'
import type { PageResult } from '@/types/auth'
import type { MediaPageQuery } from '@/types/media'

export type MediaWallFetcher<T> = (query: MediaPageQuery) => Promise<PageResult<T>>
export type MediaWallSortField = 'added' | 'release' | 'rating' | 'title'

const PAGE_SIZE = 48

export function useMediaWall<T>(storageKey: string, fetcher: MediaWallFetcher<T>) {
  const items = ref([]) as Ref<T[]>
  const loading = ref(true)
  const loadingMore = ref(false)
  const finished = ref(false)
  const sortField = ref<MediaWallSortField>('added')
  const sortOrder = ref<'asc' | 'desc'>('desc')
  const sentinel = ref<HTMLElement | null>(null)

  let pageNum = 1
  let observer: IntersectionObserver | null = null

  restoreSort()

  onMounted(() => {
    observer = new IntersectionObserver(
      (entries) => {
        if (entries[0]?.isIntersecting) loadMore()
      },
      { rootMargin: '300px' },
    )
    if (sentinel.value) observer.observe(sentinel.value)
    reload()
  })

  watch(sentinel, (el, prev) => {
    if (prev) observer?.unobserve(prev)
    if (el) observer?.observe(el)
  })

  onBeforeUnmount(() => observer?.disconnect())

  async function reload() {
    loading.value = true
    pageNum = 1
    finished.value = false
    try {
      const data = await fetchPage(1)
      items.value = data.records
      if (data.records.length >= Number(data.total)) finished.value = true
    } finally {
      loading.value = false
    }
  }

  async function loadMore() {
    if (loading.value || loadingMore.value || finished.value) return
    loadingMore.value = true
    try {
      const data = await fetchPage(pageNum + 1)
      if (data.records.length === 0) {
        finished.value = true
        return
      }
      pageNum += 1
      items.value.push(...data.records)
      if (items.value.length >= Number(data.total)) finished.value = true
    } finally {
      loadingMore.value = false
    }
  }

  function fetchPage(page: number): Promise<PageResult<T>> {
    return fetcher({
      pageNum: page,
      pageSize: PAGE_SIZE,
      sortField: sortField.value,
      sortOrder: sortOrder.value,
    })
  }

  function setSort(field: MediaWallSortField, order: 'asc' | 'desc') {
    sortField.value = field
    sortOrder.value = order
    persistSort()
    reload()
  }

  function restoreSort() {
    try {
      const saved = JSON.parse(localStorage.getItem(sortKey()) || 'null') as {
        sortField?: MediaWallSortField
        sortOrder?: 'asc' | 'desc'
      } | null
      if (
        saved?.sortField === 'added' ||
        saved?.sortField === 'release' ||
        saved?.sortField === 'rating' ||
        saved?.sortField === 'title'
      ) {
        sortField.value = saved.sortField
      }
      if (saved?.sortOrder === 'asc' || saved?.sortOrder === 'desc') sortOrder.value = saved.sortOrder
    } catch {
      // 忽略损坏的本地缓存
    }
  }

  function persistSort() {
    localStorage.setItem(sortKey(), JSON.stringify({ sortField: sortField.value, sortOrder: sortOrder.value }))
  }

  function sortKey(): string {
    return `media-wall-sort:${storageKey}`
  }

  /**
   * 模板函数式 ref 绑定哨兵元素。
   */
  function setSentinel(el: unknown) {
    sentinel.value = (el as HTMLElement | null) ?? null
  }

  return {
    items,
    loading,
    loadingMore,
    finished,
    sortField,
    sortOrder,
    sentinel,
    setSentinel,
    reload,
    setSort,
  }
}
