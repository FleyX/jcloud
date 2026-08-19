import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import type { Router } from 'vue-router'
import DeviceSessionPanel from './DeviceSessionPanel.vue'
import { listDevices, revokeDevice, logoutAll, logout } from '@/api/auth'
import type { DeviceSessionVo } from '@/types/auth'

const confirmOpen = vi.hoisted(() => vi.fn())

vi.mock('@/store/confirm', () => ({
  useConfirmStore: () => ({ open: confirmOpen }),
}))

vi.mock('@/api/auth', () => ({
  listDevices: vi.fn(),
  revokeDevice: vi.fn(),
  logoutAll: vi.fn(),
  logout: vi.fn(),
  login: vi.fn(),
  getCurrentUser: vi.fn(),
}))

function buildDevice(overrides: Partial<DeviceSessionVo> = {}): DeviceSessionVo {
  return {
    deviceId: 'd1',
    deviceName: 'Chrome',
    lastActiveTime: 1700000000000,
    current: false,
    ...overrides,
  }
}

let router: Router

async function createRouterWithLoginRoute() {
  router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/login', name: 'Login', component: { template: '<div />' } },
      { path: '/profile', name: 'PersonProfile', component: { template: '<div />' } },
    ],
  })
  await router.push('/profile')
  await router.isReady()
}

async function mountPanel() {
  const pinia = createPinia()
  const wrapper = mount(DeviceSessionPanel, {
    global: { plugins: [pinia, router] },
  })
  await flushPromises()
  return { wrapper, pinia }
}

describe('DeviceSessionPanel', () => {
  beforeEach(async () => {
    vi.clearAllMocks()
    confirmOpen.mockReset()
    await createRouterWithLoginRoute()
  })

  it('renders device name, formatted active time, current badge, and hides kick for current device', async () => {
    vi.mocked(listDevices).mockResolvedValue([
      buildDevice({ deviceId: 'd1', deviceName: 'Chrome' }),
      buildDevice({ deviceId: 'd2', deviceName: 'iPhone', current: true }),
    ])
    const { wrapper } = await mountPanel()

    expect(wrapper.text()).toContain('Chrome')
    expect(wrapper.text()).toContain('iPhone')
    expect(wrapper.text()).toContain('当前设备')
    // 1700000000000 → 2023-11-14（UTC）
    expect(wrapper.text()).toContain('2023-11-14')

    // 只有非当前设备展示「踢出」按钮
    const kickButtons = wrapper.findAll('button').filter((b) => b.text() === '踢出')
    expect(kickButtons).toHaveLength(1)
    // 当前设备所在项不含踢出按钮
    const currentItem = wrapper.findAll('li').find((li) => li.text().includes('iPhone'))!
    expect(currentItem.text()).not.toContain('踢出')
  })

  it('calls listDevices with the current deviceId on mount', async () => {
    vi.mocked(listDevices).mockResolvedValue([])
    await mountPanel()
    expect(listDevices).toHaveBeenCalledTimes(1)
    expect(vi.mocked(listDevices).mock.calls[0][0]).toBeTruthy()
  })

  it('revokes a non-current device after confirm and removes it from the list', async () => {
    vi.mocked(listDevices).mockResolvedValue([buildDevice({ deviceId: 'd1', deviceName: 'Chrome' })])
    vi.mocked(revokeDevice).mockResolvedValue(undefined)
    confirmOpen.mockResolvedValue(true)
    const { wrapper } = await mountPanel()

    await wrapper.findAll('button').find((b) => b.text() === '踢出')!.trigger('click')
    await flushPromises()

    expect(revokeDevice).toHaveBeenCalledWith('d1')
    expect(wrapper.text()).not.toContain('Chrome')
  })

  it('does not revoke when the confirm dialog is cancelled', async () => {
    vi.mocked(listDevices).mockResolvedValue([buildDevice({ deviceId: 'd1', deviceName: 'Chrome' })])
    confirmOpen.mockResolvedValue(false)
    const { wrapper } = await mountPanel()

    await wrapper.findAll('button').find((b) => b.text() === '踢出')!.trigger('click')
    await flushPromises()

    expect(confirmOpen).toHaveBeenCalled()
    expect(revokeDevice).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('Chrome')
  })

  it('logs out all devices after confirm, clears local state, and redirects to /login', async () => {
    vi.mocked(listDevices).mockResolvedValue([buildDevice({ deviceId: 'd1', deviceName: 'Chrome' })])
    vi.mocked(logoutAll).mockResolvedValue(undefined)
    vi.mocked(logout).mockResolvedValue(undefined)
    confirmOpen.mockResolvedValue(true)
    const { wrapper } = await mountPanel()

    await wrapper.findAll('button').find((b) => b.text().includes('退出所有设备'))!.trigger('click')
    await flushPromises()

    expect(logoutAll).toHaveBeenCalled()
    expect(router.currentRoute.value.path).toBe('/login')
  })

  it('does not log out all when the confirm dialog is cancelled', async () => {
    vi.mocked(listDevices).mockResolvedValue([buildDevice()])
    confirmOpen.mockResolvedValue(false)
    const { wrapper } = await mountPanel()

    await wrapper.findAll('button').find((b) => b.text().includes('退出所有设备'))!.trigger('click')
    await flushPromises()

    expect(logoutAll).not.toHaveBeenCalled()
    expect(router.currentRoute.value.path).toBe('/profile')
  })

  it('shows the empty state when there are no devices', async () => {
    vi.mocked(listDevices).mockResolvedValue([])
    const { wrapper } = await mountPanel()
    expect(wrapper.text()).toContain('暂无其他登录设备')
  })

  it('shows the error state on load failure and recovers via retry', async () => {
    vi.mocked(listDevices).mockRejectedValue(new Error('boom'))
    const { wrapper } = await mountPanel()

    expect(wrapper.text()).toContain('加载失败')

    vi.mocked(listDevices).mockResolvedValue([buildDevice({ deviceId: 'd1', deviceName: 'Chrome' })])
    await wrapper.findAll('button').find((b) => b.text() === '重试')!.trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('Chrome')
  })
})
