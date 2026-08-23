import { beforeEach, describe, expect, it, vi } from 'vitest'

const mocks = vi.hoisted(() => {
  // 守卫用 instanceof 识别 401 错误；vi.resetModules 后守卫拿到的是新模块实例，
  // 因此通过 mock 固定该类，保证测试抛出的错误与守卫判断的是同一个类
  class UnauthorizedError extends Error {}
  return {
    useUserStore: vi.fn(),
    UnauthorizedError,
  }
})

vi.mock('@/store/user', () => ({
  useUserStore: mocks.useUserStore,
}))

vi.mock('@/api/errors', () => ({
  UnauthorizedError: mocks.UnauthorizedError,
}))

vi.mock('@/utils/device', () => ({
  deviceView: () => ({ template: '<div />' }),
}))

describe('路由登录态恢复', () => {
  beforeEach(() => {
    vi.resetModules()
  })

  it('恢复用户信息暂时失败时不应清除登录态（cookie 语义），也不跳转受保护页', async () => {
    const store = {
      userInfo: null,
      dynamicRoutesAdded: false,
      initialized: true,
      isAdmin: false,
      hasResource: () => false,
      fetchCurrentUser: vi.fn().mockRejectedValue(new Error('后端尚未启动')),
      logoutAction: vi.fn(),
      markDynamicRoutesAdded: vi.fn(),
    }
    mocks.useUserStore.mockReturnValue(store)

    const { default: router } = await import('./index')
    await router.push('/files')

    expect(store.logoutAction).not.toHaveBeenCalled()
    expect(store.userInfo).toBeNull()
    // 后端未启动时停滞在守卫（next(false)），不进入 /files，也未跳转登录页
    expect(router.currentRoute.value.path).not.toBe('/files')
    expect(router.currentRoute.value.path).not.toBe('/login')
  })

  it('初始导航 401 时由守卫重定向登录页，isReady 正常完成（修复跳转登录白屏）', async () => {
    const store = {
      userInfo: null,
      dynamicRoutesAdded: false,
      initialized: true,
      isAdmin: false,
      hasResource: () => false,
      // 模拟 request 层 401：抛出 UnauthorizedError（request 层已清登录态且守卫进行中不主动 push）
      fetchCurrentUser: vi.fn().mockRejectedValue(new mocks.UnauthorizedError('登录已过期')),
      logoutAction: vi.fn(),
      markDynamicRoutesAdded: vi.fn(),
    }
    mocks.useUserStore.mockReturnValue(store)

    const { default: router, isGuardInFlight } = await import('./index')
    await router.push('/files')

    // 守卫以重定向收尾：落到登录页，且首跳正常确认，isReady 可 resolve（应用得以挂载）
    expect(router.currentRoute.value.path).toBe('/login')
    await expect(router.isReady()).resolves.toBeUndefined()
    // 守卫结束后复位进行中标记
    expect(isGuardInFlight()).toBe(false)
  })
})
