import { afterEach, describe, expect, it, vi } from 'vitest'
import { nextTick } from 'vue'
import { useDebouncedSearch } from './useDebouncedSearch'

interface Page<T> {
  records: T[]
  total: number | string
}

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason?: unknown) => void
  const promise = new Promise<T>((res, rej) => {
    resolve = res
    reject = rej
  })
  return { promise, resolve, reject }
}

afterEach(() => {
  vi.useRealTimers()
})

describe('useDebouncedSearch 防抖与竞态防护与分页', () => {
  it('防抖窗口内多次修改关键词只发一次请求（默认 300ms）', async () => {
    vi.useFakeTimers()
    const fetcher = vi.fn().mockResolvedValue({ records: ['r1'], total: 1 })
    const search = useDebouncedSearch<string>({ fetcher })

    search.keyword.value = 'a'
    await nextTick()
    search.keyword.value = 'ab'
    await nextTick()
    search.keyword.value = 'abc'
    await nextTick()
    // 窗口未到，一次请求都不发
    expect(fetcher).not.toHaveBeenCalled()

    await vi.advanceTimersByTimeAsync(300)
    expect(fetcher).toHaveBeenCalledTimes(1)
    expect(fetcher).toHaveBeenCalledWith('abc', 1, 20)
    expect(search.results.value).toEqual(['r1'])
    expect(search.searched.value).toBe(true)
    expect(search.loading.value).toBe(false)
  })

  it('防抖窗口内输入会重置计时，未到 300ms 不触发', async () => {
    vi.useFakeTimers()
    const fetcher = vi.fn().mockResolvedValue({ records: [], total: 0 })
    const search = useDebouncedSearch<string>({ fetcher, debounceMs: 300 })

    search.keyword.value = 'a'
    await nextTick()
    await vi.advanceTimersByTimeAsync(299)
    expect(fetcher).not.toHaveBeenCalled()

    search.keyword.value = 'ab'
    await nextTick()
    await vi.advanceTimersByTimeAsync(299)
    expect(fetcher).not.toHaveBeenCalled()

    await vi.advanceTimersByTimeAsync(1)
    expect(fetcher).toHaveBeenCalledTimes(1)
    expect(fetcher).toHaveBeenCalledWith('ab', 1, 20)
  })

  it('慢请求后发起新请求，旧响应到达时被丢弃', async () => {
    vi.useFakeTimers()
    const slow = deferred<Page<string>>()
    const fetcher = vi
      .fn()
      .mockImplementationOnce(() => slow.promise)
      .mockImplementationOnce(async () => ({ records: ['new'], total: 1 }))
    const search = useDebouncedSearch<string>({ fetcher })

    search.keyword.value = 'old'
    await nextTick()
    await vi.advanceTimersByTimeAsync(300)
    expect(fetcher).toHaveBeenCalledTimes(1)
    expect(search.loading.value).toBe(true)

    search.keyword.value = 'new'
    await nextTick()
    await vi.advanceTimersByTimeAsync(300)
    expect(fetcher).toHaveBeenCalledTimes(2)
    expect(search.results.value).toEqual(['new'])

    // 旧响应迟到：seq 不匹配，结果与 loading 均不被旧请求覆盖
    slow.resolve({ records: ['stale'], total: 1 })
    await nextTick()
    expect(search.results.value).toEqual(['new'])
    expect(search.loading.value).toBe(false)
  })

  it('loadMore 分页累加 records，达到 total 后 finished', async () => {
    vi.useFakeTimers()
    const fetcher = vi
      .fn()
      .mockImplementationOnce(async () => ({ records: [1, 2], total: 5 }))
      .mockImplementationOnce(async () => ({ records: [3, 4], total: 5 }))
      .mockImplementationOnce(async () => ({ records: [5], total: 5 }))
    const search = useDebouncedSearch<number>({ fetcher, pageSize: 2 })

    search.keyword.value = 'x'
    await nextTick()
    await vi.advanceTimersByTimeAsync(300)
    expect(search.results.value).toEqual([1, 2])
    expect(search.total.value).toBe(5)
    expect(search.finished.value).toBe(false)

    await search.loadMore()
    expect(fetcher).toHaveBeenLastCalledWith('x', 2, 2)
    expect(search.results.value).toEqual([1, 2, 3, 4])
    expect(search.finished.value).toBe(false)
    expect(search.loadingMore.value).toBe(false)

    await search.loadMore()
    expect(fetcher).toHaveBeenLastCalledWith('x', 3, 2)
    expect(search.results.value).toEqual([1, 2, 3, 4, 5])
    expect(search.finished.value).toBe(true)
  })

  it('reset 清空结果与状态，空关键词搜索后 searched 复位', async () => {
    vi.useFakeTimers()
    const fetcher = vi.fn().mockResolvedValue({ records: ['r1'], total: 1 })
    const search = useDebouncedSearch<string>({ fetcher })

    search.keyword.value = 'x'
    await nextTick()
    await vi.advanceTimersByTimeAsync(300)
    expect(search.searched.value).toBe(true)

    search.reset()
    expect(search.results.value).toEqual([])
    expect(search.total.value).toBe(0)
    expect(search.loading.value).toBe(false)
    expect(search.loadingMore.value).toBe(false)
    expect(search.finished.value).toBe(false)
    expect(search.searched.value).toBe(false)

    // 清空关键词触发的搜索同样复位状态
    search.keyword.value = ''
    await nextTick()
    await vi.advanceTimersByTimeAsync(300)
    expect(search.searched.value).toBe(false)
  })
})
