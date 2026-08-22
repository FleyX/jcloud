import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { get } from './request'
import { UnauthorizedError } from './errors'

const mocks = vi.hoisted(() => {
  const userStore = {
    logoutAction: vi.fn(),
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

function callInit(call: unknown[]): { headers?: Record<string, string>; credentials?: string } {
  return (call[1] as { headers?: Record<string, string>; credentials?: string } | undefined) ?? {}
}

describe('request 同源 cookie 鉴权', () => {
  beforeEach(() => {
    mocks.userStore.logoutAction.mockClear()
    mocks.routerPush.mockClear()
    mocks.notificationError.mockClear()
    mocks.guardInFlight = false
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('请求不带 Authorization 头，且携带 same-origin credentials 依赖 cookie 鉴权', async () => {
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
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse({ code: 401, msg: '登录已过期' }))
    vi.stubGlobal('fetch', fetchMock)

    await expect(get('/files/list')).rejects.toThrow('登录已过期')
    // 无刷新、无重试：仅一次请求
    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(mocks.userStore.logoutAction).toHaveBeenCalledTimes(1)
    expect(mocks.routerPush).toHaveBeenCalledWith('/login')
    expect(mocks.notificationError).not.toHaveBeenCalled()
  })

  it('守卫进行中 401 不主动 push 登录页（由守卫重定向收尾，避免与当前导航竞态）', async () => {
    mocks.guardInFlight = true
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse({ code: 401, msg: '登录已过期' }))
    vi.stubGlobal('fetch', fetchMock)

    // 仍抛出 UnauthorizedError 供守卫识别，且登录态照常清除
    await expect(get('/files/list')).rejects.toBeInstanceOf(UnauthorizedError)
    expect(mocks.userStore.logoutAction).toHaveBeenCalledTimes(1)
    expect(mocks.routerPush).not.toHaveBeenCalled()
  })

  it('业务错误（code!==200）只弹通知，不清理登录态', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse({ code: 500, msg: '服务器错误' }))
    vi.stubGlobal('fetch', fetchMock)

    await expect(get('/files/list')).rejects.toThrow('服务器错误')
    expect(mocks.userStore.logoutAction).not.toHaveBeenCalled()
    expect(mocks.routerPush).not.toHaveBeenCalled()
    expect(mocks.notificationError).toHaveBeenCalledWith('服务器错误')
  })
})
