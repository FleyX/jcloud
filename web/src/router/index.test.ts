import { beforeEach, describe, expect, it, vi } from 'vitest'

const mocks = vi.hoisted(() => ({
  useUserStore: vi.fn(),
}))

vi.mock('@/store/user', () => ({
  useUserStore: mocks.useUserStore,
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
})
