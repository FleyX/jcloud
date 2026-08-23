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

describe('user store 登录态与设备标识', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    localStorage.clear()
    vi.clearAllMocks()
  })

  it('登录成功后不回写任一 token 到 localStorage，仅持久化设备标识', async () => {
    const store = useUserStore()
    const loginMock = vi.mocked(login)
    loginMock.mockResolvedValue(buildLoginVo())

    await store.loginAction('admin', 'admin')

    expect(loginMock).toHaveBeenCalledWith({
      username: 'admin',
      password: 'admin',
      deviceId: expect.any(String),
    })
    // cookie 语义：前端不再持有 token，也不持久化
    expect(localStorage.getItem('jcloud_token')).toBeNull()
    expect(localStorage.getItem('jcloud_refresh_token')).toBeNull()
    expect(localStorage.getItem('jcloud_device_id')).toBe('device-1')
    // 设备标识以后端回显为准更新
    expect(store.deviceId).toBe('device-1')
    // 登录态以 userInfo 为准
    expect(store.isLoggedIn).toBe(true)
  })

  it('无 userInfo 时 isLoggedIn 为 false，登入后为 true', () => {
    const store = useUserStore()
    expect(store.isLoggedIn).toBe(false)
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

    // 设备标识代表设备而非会话，登出后保留
    expect(localStorage.getItem('jcloud_device_id')).toBe('device-1')
    expect(store.deviceId).toBe('device-1')
    expect(store.userInfo).toBeNull()
    expect(store.isLoggedIn).toBe(false)
  })

  it('logoutAction 调用无参 logout()（cookie 由后端清除），API 失败不影响本地清理', async () => {
    const store = useUserStore()
    vi.mocked(login).mockResolvedValue(buildLoginVo())
    await store.loginAction('admin', 'admin')

    const logoutMock = vi.mocked(logout)
    logoutMock.mockRejectedValueOnce(new Error('network error'))

    store.logoutAction()

    // 登出无参：刷新令牌由后端从 cookie 读取
    expect(logoutMock).toHaveBeenCalledWith()
    // 吊销失败（best-effort）不影响本地清理
    expect(store.userInfo).toBeNull()
    expect(store.isLoggedIn).toBe(false)
  })
})

describe('user store access 过期时间内存状态', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    localStorage.clear()
    vi.clearAllMocks()
  })

  it('登录响应携带 accessExpiresAt 时写入内存（字符串转 Number，不持久化）', async () => {
    const store = useUserStore()
    vi.mocked(login).mockResolvedValue({ ...buildLoginVo(), accessExpiresAt: '1724400000000' })

    await store.loginAction('admin', 'admin')

    expect(store.accessExpiresAt).toBe(1724400000000)
    // 仅内存态，不落 localStorage
    expect(localStorage.getItem('jcloud_access_expires_at')).toBeNull()
  })

  it('登录响应无 accessExpiresAt 时过期时间为 null', async () => {
    const store = useUserStore()
    vi.mocked(login).mockResolvedValue(buildLoginVo())

    await store.loginAction('admin', 'admin')

    expect(store.accessExpiresAt).toBeNull()
  })

  it('logoutAction 重置过期时间为 null', async () => {
    const store = useUserStore()
    vi.mocked(login).mockResolvedValue({ ...buildLoginVo(), accessExpiresAt: '1724400000000' })
    await store.loginAction('admin', 'admin')
    expect(store.accessExpiresAt).toBe(1724400000000)

    store.logoutAction()

    expect(store.accessExpiresAt).toBeNull()
  })
})
