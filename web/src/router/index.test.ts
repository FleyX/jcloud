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

  it('恢复用户信息暂时失败时不应清除已持久化的 token', async () => {
    const store = {
      token: 'persisted-token',
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
    expect(store.token).toBe('persisted-token')
  })
})
