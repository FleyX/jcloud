import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'
import NotificationBell from './NotificationBell.vue'
import { useUserStore } from '@/store/user'
import type { NotificationItem } from '@/types/notification'
import type { UserVo } from '@/types/auth'

const apiMocks = vi.hoisted(() => ({
  listNotifications: vi.fn(),
  getUnreadCount: vi.fn(),
  markNotificationRead: vi.fn(),
  markAllNotificationsRead: vi.fn(),
}))

vi.mock('@/api/notification', () => apiMocks)

function buildUser(): UserVo {
  return { id: '1', username: 'admin', status: 1, isAdmin: true, roles: [] }
}

function buildItem(id: string, title: string, isRead = false): NotificationItem {
  return {
    id,
    eventType: 'transfer_completed',
    title,
    content: `${title} 的内容`,
    isRead,
    createTime: '2026-10-04 10:00:00',
  }
}

async function mountBell(): Promise<{ wrapper: VueWrapper; router: Router }> {
  localStorage.clear()
  const pinia = createPinia()
  setActivePinia(pinia)
  useUserStore().userInfo = buildUser()

  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/files', component: { template: '<div />' } },
      { path: '/notifications', component: { template: '<div />' } },
    ],
  })
  await router.push('/files')
  await router.isReady()

  const wrapper = mount(NotificationBell, { global: { plugins: [pinia, router] } })
  return { wrapper, router }
}

function badgeOf(wrapper: VueWrapper): string {
  return wrapper.find('button[aria-label="通知"]').text()
}

describe('NotificationBell', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    apiMocks.getUnreadCount.mockResolvedValue('0')
    apiMocks.listNotifications.mockResolvedValue([])
    apiMocks.markNotificationRead.mockResolvedValue(undefined)
    apiMocks.markAllNotificationsRead.mockResolvedValue(undefined)
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('renders the unread badge and refreshes it every 30 seconds', async () => {
    vi.useFakeTimers()
    apiMocks.getUnreadCount.mockResolvedValue('2')
    const { wrapper } = await mountBell()
    await flushPromises()
    expect(badgeOf(wrapper)).toContain('2')

    apiMocks.getUnreadCount.mockResolvedValue('105')
    await vi.advanceTimersByTimeAsync(30_000)
    expect(badgeOf(wrapper)).toContain('99+')

    wrapper.unmount()
  })

  it('marks a single notification read when clicked in the panel', async () => {
    apiMocks.getUnreadCount.mockResolvedValue('1')
    apiMocks.listNotifications.mockResolvedValue([buildItem('n1', '传输完成')])
    const { wrapper } = await mountBell()
    await flushPromises()

    await wrapper.find('button[aria-label="通知"]').trigger('click')
    await flushPromises()

    const item = wrapper.findAll('button').find((button) => button.text().includes('传输完成'))
    expect(item).toBeDefined()
    await item?.trigger('click')
    await flushPromises()

    expect(apiMocks.markNotificationRead).toHaveBeenCalledWith('n1')
    expect(badgeOf(wrapper)).not.toContain('1')
  })

  it('marks all notifications read and clears the badge', async () => {
    apiMocks.getUnreadCount.mockResolvedValue('2')
    apiMocks.listNotifications.mockResolvedValue([buildItem('n1', '通知一'), buildItem('n2', '通知二')])
    const { wrapper } = await mountBell()
    await flushPromises()

    await wrapper.find('button[aria-label="通知"]').trigger('click')
    await flushPromises()

    const readAll = wrapper.findAll('button').find((button) => button.text() === '全部已读')
    await readAll?.trigger('click')
    await flushPromises()

    expect(apiMocks.markAllNotificationsRead).toHaveBeenCalledTimes(1)
    expect(wrapper.find('span.bg-red-500').exists()).toBe(false)
  })

  it('navigates to the full notifications page', async () => {
    const { wrapper, router } = await mountBell()
    await flushPromises()

    await wrapper.find('button[aria-label="通知"]').trigger('click')
    await flushPromises()
    const viewAll = wrapper.findAll('button').find((button) => button.text() === '查看全部')
    await viewAll?.trigger('click')
    await flushPromises()

    expect(router.currentRoute.value.path).toBe('/notifications')
  })
})
