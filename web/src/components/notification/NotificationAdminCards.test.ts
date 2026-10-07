import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import NotificationEventSwitchCard from './NotificationEventSwitchCard.vue'
import NotificationSendLogCard from './NotificationSendLogCard.vue'
import type { NotificationEventSwitch, NotificationEventType, NotificationSendLog } from '@/types/notification'

const apiMocks = vi.hoisted(() => ({
  fetchNotificationEventSwitches: vi.fn(),
  updateNotificationEventSwitch: vi.fn(),
  fetchNotificationSendLogs: vi.fn(),
}))

vi.mock('@/api/notification', () => apiMocks)

function buildSwitch(eventType: NotificationEventType, name: string, enabled: boolean): NotificationEventSwitch {
  return { eventType, name, enabled }
}

function buildLog(id: string, success: boolean): NotificationSendLog {
  return {
    id,
    eventType: 'transfer_completed',
    eventTypeName: '跨来源传输完成',
    recipient: 'user@example.com',
    subject: '跨来源传输完成',
    success,
    errorMessage: success ? null : '邮件发送失败',
    createTime: '2026-10-05 09:00:00',
  }
}

function mountWithPinia(component: Parameters<typeof mount>[0]): VueWrapper {
  const pinia = createPinia()
  setActivePinia(pinia)
  return mount(component, { global: { plugins: [pinia] } })
}

describe('NotificationEventSwitchCard', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    apiMocks.updateNotificationEventSwitch.mockResolvedValue(undefined)
  })

  it('renders event switches and persists the toggled state', async () => {
    apiMocks.fetchNotificationEventSwitches.mockResolvedValue([
      buildSwitch('transfer_completed', '跨来源传输完成', true),
      buildSwitch('quota_alert', '配额告警', false),
    ])
    const wrapper = mountWithPinia(NotificationEventSwitchCard)
    await flushPromises()

    expect(wrapper.text()).toContain('跨来源传输完成')
    expect(wrapper.text()).toContain('配额告警')

    const switches = wrapper.findAll('button[role="switch"]')
    expect(switches).toHaveLength(2)

    await switches[0].trigger('click')
    await flushPromises()

    expect(apiMocks.updateNotificationEventSwitch).toHaveBeenCalledWith('transfer_completed', false)
    expect(switches[0].attributes('data-state')).toBe('unchecked')
  })

  it('reverts the switch when saving fails', async () => {
    apiMocks.fetchNotificationEventSwitches.mockResolvedValue([
      buildSwitch('transfer_completed', '跨来源传输完成', true),
    ])
    apiMocks.updateNotificationEventSwitch.mockRejectedValue(new Error('保存失败'))
    const wrapper = mountWithPinia(NotificationEventSwitchCard)
    await flushPromises()

    const toggle = wrapper.find('button[role="switch"]')
    await toggle.trigger('click')
    await flushPromises()

    expect(toggle.attributes('data-state')).toBe('checked')
  })
})

describe('NotificationSendLogCard', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('renders send logs with result labels', async () => {
    apiMocks.fetchNotificationSendLogs.mockResolvedValue({
      records: [buildLog('1', true), buildLog('2', false)],
      total: '2',
      size: '10',
      current: '1',
      pages: '1',
    })
    const wrapper = mountWithPinia(NotificationSendLogCard)
    await flushPromises()

    expect(wrapper.text()).toContain('成功')
    expect(wrapper.text()).toContain('失败')
    expect(wrapper.text()).toContain('邮件发送失败')
    expect(wrapper.findAll('tbody tr')).toHaveLength(2)
  })

  it('shows empty state when no logs exist', async () => {
    apiMocks.fetchNotificationSendLogs.mockResolvedValue({
      records: [],
      total: '0',
      size: '10',
      current: '1',
      pages: '0',
    })
    const wrapper = mountWithPinia(NotificationSendLogCard)
    await flushPromises()

    expect(wrapper.text()).toContain('暂无发送记录')
  })
})
