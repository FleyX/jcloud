import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { get, post } from './request'
import { UnauthorizedError } from './errors'

const mocks = vi.hoisted(() => {
  const userStore = {
    logoutAction: vi.fn(),
    accessExpiresAt: null as number | null,
  }
  return {
    userStore,
    routerPush: vi.fn(),
    notificationError: vi.fn(),
    guardInFlight: false,
  }
})

vi.mock('@/store/user', () => ({
  useUserStore: () => mocks.userStore,
}))

vi.mock('@/store/notification', () => ({
  useNotificationStore: () => ({ error: mocks.notificationError }),
}))

vi.mock('@/router', () => ({
  default: { push: mocks.routerPush },
  isGuardInFlight: () => mocks.guardInFlight,
}))

/** 构造与 Response 兼容的假响应（仅 handleResponse 使用的 json 方法） */
function jsonResponse(body: unknown) {
  return { json: async () => body }
}

/** 模拟刷新接口的成功响应（accessExpiresAt 为字符串，与后端 Long 序列化一致） */
function refreshResponse(accessExpiresAt: string) {
  return jsonResponse({ code: 200, msg: 'ok', data: { token: 't', refreshToken: 'r', accessExpiresAt } })
}

/** 模拟剩余有效期充足的 access（预检直接放行，不触发刷新） */
function setFreshAccess() {
  mocks.userStore.accessExpiresAt = Date.now() + 60 * 60 * 1000
}

function callInit(call: unknown[]): { headers?: Record<string, string>; credentials?: string } {
  return (call[1] as { headers?: Record<string, string>; credentials?: string } | undefined) ?? {}
}

describe('request 同源 cookie 鉴权', () => {
  beforeEach(() => {
    mocks.userStore.logoutAction.mockClear()
    mocks.userStore.accessExpiresAt = null
    mocks.routerPush.mockClear()
    mocks.notificationError.mockClear()
    mocks.guardInFlight = false
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('请求不带 Authorization 头，且携带 same-origin credentials 依赖 cookie 鉴权', async () => {
    setFreshAccess()
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse({ code: 200, msg: 'ok', data: { id: 'file-1' } }))
    vi.stubGlobal('fetch', fetchMock)

    const result = await get<{ id: string }>('/files/detail', { page: 1 })

    const init = callInit(fetchMock.mock.calls[0])
    expect(init.headers?.Authorization).toBeUndefined()
    expect(init.credentials).toBe('same-origin')
    expect(result).toEqual({ id: 'file-1' })
    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(mocks.notificationError).not.toHaveBeenCalled()
  })

  it('401 时清除登录态并跳转登录页，请求失败且不重试', async () => {
    setFreshAccess()
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse({ code: 401, msg: '登录已过期' }))
    vi.stubGlobal('fetch', fetchMock)

    await expect(get('/files/list')).rejects.toThrow('登录已过期')
    // 行为反转点：401 不调刷新、不重试——仅一次请求且非刷新接口
    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(String(fetchMock.mock.calls[0][0])).not.toContain('/auth/refresh')
    expect(mocks.userStore.logoutAction).toHaveBeenCalledTimes(1)
    expect(mocks.routerPush).toHaveBeenCalledWith('/login')
    expect(mocks.notificationError).not.toHaveBeenCalled()
  })

  it('守卫进行中 401 不主动 push 登录页（由守卫重定向收尾，避免与当前导航竞态）', async () => {
    mocks.guardInFlight = true
    setFreshAccess()
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse({ code: 401, msg: '登录已过期' }))
    vi.stubGlobal('fetch', fetchMock)

    // 仍抛出 UnauthorizedError 供守卫识别，且登录态照常清除
    await expect(get('/files/list')).rejects.toBeInstanceOf(UnauthorizedError)
    expect(mocks.userStore.logoutAction).toHaveBeenCalledTimes(1)
    expect(mocks.routerPush).not.toHaveBeenCalled()
  })

  it('业务错误（code!==200）只弹通知，不清理登录态', async () => {
    setFreshAccess()
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse({ code: 500, msg: '服务器错误' }))
    vi.stubGlobal('fetch', fetchMock)

    await expect(get('/files/list')).rejects.toThrow('服务器错误')
    expect(mocks.userStore.logoutAction).not.toHaveBeenCalled()
    expect(mocks.routerPush).not.toHaveBeenCalled()
    expect(mocks.notificationError).toHaveBeenCalledWith('服务器错误')
  })
})

describe('request 预检刷新', () => {
  beforeEach(() => {
    mocks.userStore.logoutAction.mockClear()
    mocks.userStore.accessExpiresAt = null
    mocks.routerPush.mockClear()
    mocks.notificationError.mockClear()
    mocks.guardInFlight = false
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('access 剩余有效期充足时业务请求直接发出，不调用刷新接口', async () => {
    setFreshAccess()
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse({ code: 200, msg: 'ok', data: { id: 'file-1' } }))
    vi.stubGlobal('fetch', fetchMock)

    const result = await get<{ id: string }>('/files/detail')

    expect(result).toEqual({ id: 'file-1' })
    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(String(fetchMock.mock.calls[0][0])).not.toContain('/auth/refresh')
  })

  it('临期时先刷新再发原请求，并更新内存过期时间（字符串转 Number）', async () => {
    mocks.userStore.accessExpiresAt = Date.now() + 60 * 1000 // 剩余 1 分钟 < 10 分钟阈值
    const newExpiresAt = String(Date.now() + 30 * 60 * 1000)
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(refreshResponse(newExpiresAt))
      .mockResolvedValueOnce(jsonResponse({ code: 200, msg: 'ok', data: { id: 'file-1' } }))
    vi.stubGlobal('fetch', fetchMock)

    const result = await get<{ id: string }>('/files/detail')

    // 先刷新（POST /auth/refresh、空 body），再发原请求
    expect(String(fetchMock.mock.calls[0][0])).toContain('/auth/refresh')
    expect(callInit(fetchMock.mock.calls[0]).credentials).toBe('same-origin')
    expect(String(fetchMock.mock.calls[1][0])).toContain('/files/detail')
    expect(fetchMock).toHaveBeenCalledTimes(2)
    // store 内存过期时间更新为刷新响应中的新值（字符串转 Number）
    expect(mocks.userStore.accessExpiresAt).toBe(Number(newExpiresAt))
    expect(result).toEqual({ id: 'file-1' })
  })

  it('并发业务请求共享同一次刷新（单飞）', async () => {
    mocks.userStore.accessExpiresAt = Date.now() + 60 * 1000
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(refreshResponse(String(Date.now() + 30 * 60 * 1000)))
      .mockResolvedValueOnce(jsonResponse({ code: 200, msg: 'ok', data: { id: 'f1' } }))
      .mockResolvedValueOnce(jsonResponse({ code: 200, msg: 'ok', data: { id: 'f2' } }))
    vi.stubGlobal('fetch', fetchMock)

    const [r1, r2] = await Promise.all([
      get<{ id: string }>('/files/detail/1'),
      get<{ id: string }>('/files/detail/2'),
    ])

    expect(r1).toEqual({ id: 'f1' })
    expect(r2).toEqual({ id: 'f2' })
    // 刷新仅一次：1 次刷新 + 2 次业务请求
    expect(fetchMock).toHaveBeenCalledTimes(3)
    const refreshCalls = fetchMock.mock.calls.filter(([url]) => String(url).includes('/auth/refresh'))
    expect(refreshCalls).toHaveLength(1)
  })

  it('无过期时间（页面刚刷新）时首个业务请求先兜底刷新一次', async () => {
    mocks.userStore.accessExpiresAt = null
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(refreshResponse(String(Date.now() + 30 * 60 * 1000)))
      .mockResolvedValueOnce(jsonResponse({ code: 200, msg: 'ok', data: { id: 'file-1' } }))
    vi.stubGlobal('fetch', fetchMock)

    const result = await get<{ id: string }>('/files/detail')

    expect(String(fetchMock.mock.calls[0][0])).toContain('/auth/refresh')
    expect(String(fetchMock.mock.calls[1][0])).toContain('/files/detail')
    expect(fetchMock).toHaveBeenCalledTimes(2)
    expect(mocks.userStore.accessExpiresAt).not.toBeNull()
    expect(result).toEqual({ id: 'file-1' })
  })

  it('豁免路径（/auth/login 精确、/s/ 前缀）不触发预检，即使无过期时间', async () => {
    mocks.userStore.accessExpiresAt = null
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse({ code: 200, msg: 'ok', data: { token: 'x' } }))
      .mockResolvedValueOnce(jsonResponse({ code: 200, msg: 'ok', data: {} }))
    vi.stubGlobal('fetch', fetchMock)

    await post('/auth/login', { username: 'a', password: 'b' })
    await get('/s/share/abc')

    expect(fetchMock).toHaveBeenCalledTimes(2)
    expect(fetchMock.mock.calls.every(([url]) => !String(url).includes('/auth/refresh'))).toBe(true)
  })
})
