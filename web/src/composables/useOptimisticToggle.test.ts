import { beforeEach, describe, expect, it, vi } from 'vitest'

const mocks = vi.hoisted(() => ({
  updateMediaWatched: vi.fn(),
}))

vi.mock('@/api/media', () => mocks)

import { useOptimisticToggle } from './useOptimisticToggle'

/** 组装一个带本地状态（watched + progressMs）的中性实例 */
function build(overrides: Partial<Parameters<typeof useOptimisticToggle>[0]> = {}) {
  const state = { watched: false, progressMs: 50 }
  const onSuccess = vi.fn()
  const ctx = useOptimisticToggle({
    isWatched: () => state.watched,
    setWatched: (value) => {
      state.watched = value
    },
    progress: {
      get: () => state.progressMs,
      set: (value) => {
        state.progressMs = value ?? 0
      },
    },
    toggle: (value) => mocks.updateMediaWatched('item-1', value),
    onSuccess,
    ...overrides,
  })
  return { ctx, state, onSuccess }
}

beforeEach(() => {
  vi.clearAllMocks()
  mocks.updateMediaWatched.mockResolvedValue(undefined)
})

describe('useOptimisticToggle 已观看乐观切换', () => {
  it('成功后保留乐观置位并触发 onSuccess', async () => {
    const { ctx, state, onSuccess } = build()

    await ctx.toggle()

    expect(state.watched).toBe(true)
    expect(mocks.updateMediaWatched).toHaveBeenCalledWith('item-1', true)
    expect(onSuccess).toHaveBeenCalledTimes(1)
  })

  it('标记已观看时本地清零进度', async () => {
    const { ctx, state } = build()

    await ctx.toggle()

    expect(state.watched).toBe(true)
    expect(state.progressMs).toBe(0)
  })

  it('失败时回滚 watched 与进度、不触发 onSuccess', async () => {
    const { ctx, state, onSuccess } = build()
    mocks.updateMediaWatched.mockRejectedValueOnce(new Error('network'))

    await ctx.toggle()

    expect(state.watched).toBe(false)
    expect(state.progressMs).toBe(50)
    expect(onSuccess).not.toHaveBeenCalled()
  })

  it('取消标记时不清理进度且以 false 调后端', async () => {
    const state = { watched: true, progressMs: 80 }
    const ctx = useOptimisticToggle({
      isWatched: () => state.watched,
      setWatched: (value) => {
        state.watched = value
      },
      progress: {
        get: () => state.progressMs,
        set: (value) => {
          state.progressMs = value ?? 0
        },
      },
      toggle: (value) => mocks.updateMediaWatched('item-1', value),
    })

    await ctx.toggle()

    expect(state.watched).toBe(false)
    expect(state.progressMs).toBe(80)
    expect(mocks.updateMediaWatched).toHaveBeenCalledWith('item-1', false)
  })
})
