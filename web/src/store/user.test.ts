import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useUserStore } from './user'
import { getCurrentUser } from '@/api/auth'
import type { LoginVo } from '@/types/auth'

vi.mock('@/api/auth', () => ({
  login: vi.fn(),
  getCurrentUser: vi.fn(),
}))

function buildLoginVo(): LoginVo {
  return {
    token: 'token',
    userInfo: {
      id: '1',
      username: 'admin',
      status: 1,
      isAdmin: true,
      roles: [],
    },
    resources: [],
    initialized: true,
  }
}

describe('user store scheduleUserInfoRefresh', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.useFakeTimers()
    vi.clearAllMocks()
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('连续调用多次只触发一次 getCurrentUser（防抖合并）', () => {
    const store = useUserStore()
    const getCurrentUserMock = vi.mocked(getCurrentUser)
    getCurrentUserMock.mockResolvedValue(buildLoginVo())

    store.scheduleUserInfoRefresh()
    store.scheduleUserInfoRefresh()
    store.scheduleUserInfoRefresh()

    expect(getCurrentUserMock).not.toHaveBeenCalled()

    vi.advanceTimersByTime(799)
    expect(getCurrentUserMock).not.toHaveBeenCalled()

    vi.advanceTimersByTime(1)
    expect(getCurrentUserMock).toHaveBeenCalledTimes(1)
  })

  it('防抖到期后刷新 userInfo', async () => {
    const store = useUserStore()
    const getCurrentUserMock = vi.mocked(getCurrentUser)
    getCurrentUserMock.mockResolvedValue(buildLoginVo())

    store.scheduleUserInfoRefresh()
    await vi.advanceTimersByTimeAsync(800)

    expect(getCurrentUserMock).toHaveBeenCalledTimes(1)
    expect(store.userInfo).toEqual(buildLoginVo().userInfo)
  })
})
