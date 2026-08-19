import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useUserStore } from './user'
import { getCurrentUser, login, logout } from '@/api/auth'
import type { LoginVo } from '@/types/auth'

vi.mock('@/api/auth', () => ({
  login: vi.fn(),
  getCurrentUser: vi.fn(),
  logout: vi.fn().mockResolvedValue(undefined),
}))

function buildLoginVo(): LoginVo {
  return {
    token: 'token',
    refreshToken: 'refresh-token',
    deviceId: 'device-1',
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

describe('user store 双令牌持久化', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    localStorage.clear()
    vi.clearAllMocks()
  })

  it('登录成功后持久化访问令牌、刷新令牌与设备标识，登录携带 deviceId', async () => {
    const store = useUserStore()
    const loginMock = vi.mocked(login)
    loginMock.mockResolvedValue(buildLoginVo())

    await store.loginAction('admin', 'admin')

    expect(loginMock).toHaveBeenCalledWith({
      username: 'admin',
      password: 'admin',
      deviceId: expect.any(String),
    })
    expect(localStorage.getItem('jcloud_token')).toBe('token')
    expect(localStorage.getItem('jcloud_refresh_token')).toBe('refresh-token')
    expect(localStorage.getItem('jcloud_device_id')).toBe('device-1')
    expect(store.refreshToken).toBe('refresh-token')
    // 设备标识以后端回显为准更新
    expect(store.deviceId).toBe('device-1')
  })

  it('deviceId 首次生成后持久化，二次初始化复用同一标识', () => {
    const store1 = useUserStore()
    const generated = store1.deviceId
    expect(generated).toMatch(/^[0-9a-f-]{36}$/i)
    expect(localStorage.getItem('jcloud_device_id')).toBe(generated)

    // 销毁 Store 重新初始化：读取已持久化的标识，不再重新生成
    setActivePinia(createPinia())
    const store2 = useUserStore()
    expect(store2.deviceId).toBe(generated)

    // 存量标识同样被复用
    setActivePinia(createPinia())
    localStorage.setItem('jcloud_device_id', 'existing-device')
    const store3 = useUserStore()
    expect(store3.deviceId).toBe('existing-device')
  })

  it('logoutAction 清除登录态但保留设备标识', async () => {
    const store = useUserStore()
    vi.mocked(login).mockResolvedValue(buildLoginVo())
    await store.loginAction('admin', 'admin')

    store.logoutAction()

    expect(localStorage.getItem('jcloud_token')).toBeNull()
    expect(localStorage.getItem('jcloud_refresh_token')).toBeNull()
    // 设备标识代表设备而非会话，登出后保留
    expect(localStorage.getItem('jcloud_device_id')).toBe('device-1')
    expect(store.token).toBe('')
    expect(store.refreshToken).toBe('')
    expect(store.deviceId).toBe('device-1')
  })

  it('logoutAction 调用 logout API 携带当前刷新令牌，API 失败不影响本地清理', async () => {
    const store = useUserStore()
    vi.mocked(login).mockResolvedValue(buildLoginVo())
    await store.loginAction('admin', 'admin')

    const logoutMock = vi.mocked(logout)
    logoutMock.mockRejectedValueOnce(new Error('network error'))

    store.logoutAction()

    // 登出前先携带当前刷新令牌调用吊销接口
    expect(logoutMock).toHaveBeenCalledWith('refresh-token')
    // 吊销失败（best-effort）不影响本地清理
    expect(localStorage.getItem('jcloud_token')).toBeNull()
    expect(localStorage.getItem('jcloud_refresh_token')).toBeNull()
    expect(store.token).toBe('')
    expect(store.refreshToken).toBe('')
  })

  it('applyTokenPair 更新访问令牌与刷新令牌并持久化', () => {
    const store = useUserStore()
    store.applyTokenPair({ token: 'new-token', refreshToken: 'new-refresh' })

    expect(store.token).toBe('new-token')
    expect(store.refreshToken).toBe('new-refresh')
    expect(localStorage.getItem('jcloud_token')).toBe('new-token')
    expect(localStorage.getItem('jcloud_refresh_token')).toBe('new-refresh')
  })
})
