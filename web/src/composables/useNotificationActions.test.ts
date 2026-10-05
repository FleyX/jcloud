import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ref } from 'vue'
import { useNotificationActions } from './useNotificationActions'
import type { NotificationItem } from '@/types/notification'

const mocks = vi.hoisted(() => ({
  markNotificationRead: vi.fn(),
  markAllNotificationsRead: vi.fn(),
}))

vi.mock('@/api/notification', () => mocks)

function buildItem(id: string, isRead = false): NotificationItem {
  return {
    id,
    eventType: 'transfer_completed',
    title: `通知 ${id}`,
    content: null,
    isRead,
    createTime: '2026-10-05 09:00:00',
  }
}

function buildContext(items: NotificationItem[], unreadCount?: number) {
  const itemsRef = ref<NotificationItem[]>(items)
  const unreadRef = unreadCount === undefined ? undefined : ref(unreadCount)
  const ctx = useNotificationActions({ items: itemsRef, unreadCount: unreadRef })
  return { ctx, itemsRef, unreadRef }
}

beforeEach(() => {
  vi.clearAllMocks()
  mocks.markNotificationRead.mockResolvedValue(undefined)
  mocks.markAllNotificationsRead.mockResolvedValue(undefined)
})

describe('useNotificationActions', () => {
  describe('markRead', () => {
    it('乐观置读并递减角标，成功后保留', async () => {
      const item = buildItem('n1')
      const { ctx, unreadRef } = buildContext([item], 3)

      await ctx.markRead(item)

      expect(item.isRead).toBe(true)
      expect(unreadRef?.value).toBe(2)
      expect(mocks.markNotificationRead).toHaveBeenCalledWith('n1')
    })

    it('失败时回滚已读状态与角标', async () => {
      mocks.markNotificationRead.mockRejectedValueOnce(new Error('network'))
      const item = buildItem('n1')
      const { ctx, unreadRef } = buildContext([item], 3)

      await ctx.markRead(item)

      expect(item.isRead).toBe(false)
      expect(unreadRef?.value).toBe(3)
    })

    it('已读条目直接跳过，不调接口', async () => {
      const item = buildItem('n1', true)
      const { ctx, unreadRef } = buildContext([item], 0)

      await ctx.markRead(item)

      expect(mocks.markNotificationRead).not.toHaveBeenCalled()
      expect(unreadRef?.value).toBe(0)
    })

    it('角标为 0 时不会递减为负数', async () => {
      const item = buildItem('n1')
      const { ctx, unreadRef } = buildContext([item], 0)

      await ctx.markRead(item)

      expect(unreadRef?.value).toBe(0)
    })

    it('未传角标时只更新列表状态', async () => {
      const item = buildItem('n1')
      const { ctx, itemsRef } = buildContext([item])

      await ctx.markRead(item)

      expect(itemsRef.value[0].isRead).toBe(true)
    })
  })

  describe('markAllRead', () => {
    it('乐观全部置读并清零角标', async () => {
      const items = [buildItem('n1'), buildItem('n2')]
      const { ctx, unreadRef } = buildContext(items, 2)

      await ctx.markAllRead()

      expect(items.every((item) => item.isRead)).toBe(true)
      expect(unreadRef?.value).toBe(0)
      expect(mocks.markAllNotificationsRead).toHaveBeenCalledTimes(1)
    })

    it('失败时回滚列表已读状态与角标', async () => {
      mocks.markAllNotificationsRead.mockRejectedValueOnce(new Error('network'))
      const items = [buildItem('n1'), buildItem('n2', true)]
      const { ctx, unreadRef } = buildContext(items, 2)

      await ctx.markAllRead()

      expect(items.map((item) => item.isRead)).toEqual([false, true])
      expect(unreadRef?.value).toBe(2)
    })

    it('无未读且角标为 0 时不调接口', async () => {
      const items = [buildItem('n1', true)]
      const { ctx } = buildContext(items, 0)

      await ctx.markAllRead()

      expect(mocks.markAllNotificationsRead).not.toHaveBeenCalled()
    })

    it('列表全已读但角标大于 0（列表被截断）时仍调接口并清零角标', async () => {
      const items = [buildItem('n1', true), buildItem('n2', true)]
      const { ctx, unreadRef } = buildContext(items, 5)

      await ctx.markAllRead()

      expect(mocks.markAllNotificationsRead).toHaveBeenCalledTimes(1)
      expect(unreadRef?.value).toBe(0)
    })
  })
})
