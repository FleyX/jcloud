import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { get } from './request'

const mocks = vi.hoisted(() => {
  const userStore = {
    token: '',
    refreshToken: '',
    applyTokenPair: vi.fn((pair: { token: string; refreshToken: string }) => {
      userStore.token = pair.token
      userStore.refreshToken = pair.refreshToken
    }),
    logoutAction: vi.fn(),
  }
  return {
    userStore,
    routerPush: vi.fn(),
    notificationError: vi.fn(),
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
}))

/** 构造与 Response 兼容的假响应（仅 handleResponse 使用的 json 方法） */
function jsonResponse(body: unknown) {
  return { json: async () => body }
}

function callHeaders(call: unknown[]): Record<string, string> {
  return (call[1] as { headers?: Record<string, string> } | undefined)?.headers ?? {}
}

describe('request 静默刷新', () => {
  beforeEach(() => {
    mocks.userStore.token = ''
    mocks.userStore.refreshToken = ''
    mocks.userStore.applyTokenPair.mockClear()
    mocks.userStore.logoutAction.mockClear()
    mocks.routerPush.mockClear()
    mocks.notificationError.mockClear()
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('401 时静默刷新并以新 token 重发原请求，返回业务数据，无通知无跳转', async () => {
    mocks.userStore.token = 'old-token'
    mocks.userStore.refreshToken = 'refresh-1'

    const fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)
    let refreshCount = 0
    fetchMock.mockImplementation(async (url: string, init?: RequestInit) => {
      if (String(url).endsWith('/auth/refresh')) {
        refreshCount++
        return jsonResponse({
          code: 200,
          msg: 'ok',
          data: { token: 'new-token', refreshToken: 'refresh-2' },
        })
      }
      if (callHeaders([url, init]).Authorization === 'Bearer old-token') {
        return jsonResponse({ code: 401, msg: '登录已过期' })
      }
      return jsonResponse({ code: 200, msg: 'ok', data: { id: 'file-1', name: 'a.txt' } })
    })

    const result = await get<{ id: string; name: string }>('/files/detail')

    expect(refreshCount).toBe(1)
    expect(fetchMock).toHaveBeenCalledTimes(3)
    // 原请求使用旧 token，重发使用换新后的 token
    expect(callHeaders(fetchMock.mock.calls[0]).Authorization).toBe('Bearer old-token')
    expect(callHeaders(fetchMock.mock.calls[2]).Authorization).toBe('Bearer new-token')
    expect(mocks.userStore.applyTokenPair).toHaveBeenCalledWith({
      token: 'new-token',
      refreshToken: 'refresh-2',
    })
    expect(result).toEqual({ id: 'file-1', name: 'a.txt' })
    expect(mocks.notificationError).not.toHaveBeenCalled()
    expect(mocks.routerPush).not.toHaveBeenCalled()
  })

  it('并发请求同遇 401 只发起一次刷新，两者均重发成功', async () => {
    mocks.userStore.token = 'old-token'
    mocks.userStore.refreshToken = 'refresh-1'

    const fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)
    let refreshCount = 0
    fetchMock.mockImplementation(async (url: string, init?: RequestInit) => {
      if (String(url).endsWith('/auth/refresh')) {
        refreshCount++
        return jsonResponse({
          code: 200,
          msg: 'ok',
          data: { token: 'new-token', refreshToken: 'refresh-2' },
        })
      }
      if (callHeaders([url, init]).Authorization === 'Bearer old-token') {
        return jsonResponse({ code: 401, msg: '登录已过期' })
      }
      const key = String(url).endsWith('/b') ? 'b' : 'a'
      return jsonResponse({ code: 200, msg: 'ok', data: { key, value: `data-${key}` } })
    })

    const [ra, rb] = await Promise.all([get('/a'), get('/b')])

    expect(refreshCount).toBe(1)
    expect(fetchMock).toHaveBeenCalledTimes(5)
    expect(fetchMock.mock.calls.filter((c) => String(c[0]).endsWith('/a'))).toHaveLength(2)
    expect(fetchMock.mock.calls.filter((c) => String(c[0]).endsWith('/b'))).toHaveLength(2)
    expect(ra).toEqual({ key: 'a', value: 'data-a' })
    expect(rb).toEqual({ key: 'b', value: 'data-b' })
    expect(mocks.notificationError).not.toHaveBeenCalled()
    expect(mocks.routerPush).not.toHaveBeenCalled()
  })

  it('刷新返回 401 时清除登录态并跳转登录页，请求失败', async () => {
    mocks.userStore.token = 'old-token'
    mocks.userStore.refreshToken = 'refresh-1'

    const fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)
    fetchMock.mockImplementation(async (url: string) => {
      if (String(url).endsWith('/auth/refresh')) {
        return jsonResponse({ code: 401, msg: '刷新令牌已过期' })
      }
      return jsonResponse({ code: 401, msg: '登录已过期' })
    })

    await expect(get('/files/list')).rejects.toThrow('登录已过期')
    expect(mocks.userStore.logoutAction).toHaveBeenCalledTimes(1)
    expect(mocks.routerPush).toHaveBeenCalledWith('/login')
    expect(mocks.notificationError).not.toHaveBeenCalled()
  })

  it('刷新网络错误时清除登录态并跳转登录页，请求失败', async () => {
    mocks.userStore.token = 'old-token'
    mocks.userStore.refreshToken = 'refresh-1'

    const fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)
    fetchMock.mockImplementation(async (url: string) => {
      if (String(url).endsWith('/auth/refresh')) {
        throw new TypeError('fetch failed')
      }
      return jsonResponse({ code: 401, msg: '登录已过期' })
    })

    await expect(get('/files/list')).rejects.toThrow('登录已过期')
    expect(mocks.userStore.logoutAction).toHaveBeenCalledTimes(1)
    expect(mocks.routerPush).toHaveBeenCalledWith('/login')
    expect(mocks.notificationError).not.toHaveBeenCalled()
  })

  it('重发后仍 401 走失败路径，不无限循环', async () => {
    mocks.userStore.token = 'old-token'
    mocks.userStore.refreshToken = 'refresh-1'

    const fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)
    fetchMock.mockImplementation(async (url: string) => {
      if (String(url).endsWith('/auth/refresh')) {
        return jsonResponse({
          code: 200,
          msg: 'ok',
          data: { token: 'new-token', refreshToken: 'refresh-2' },
        })
      }
      return jsonResponse({ code: 401, msg: '登录已过期' })
    })

    await expect(get('/files/list')).rejects.toThrow('登录已过期')
    // 原请求 + 刷新 + 重发一次，无第四次请求
    expect(fetchMock).toHaveBeenCalledTimes(3)
    expect(mocks.userStore.logoutAction).toHaveBeenCalledTimes(1)
    expect(mocks.routerPush).toHaveBeenCalledWith('/login')
    expect(mocks.notificationError).not.toHaveBeenCalled()
  })
})