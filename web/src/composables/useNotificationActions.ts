/**
 * 站内通知操作 composable：标记单条已读 / 全部已读的乐观更新与失败回滚。
 * - 通知铃铛下拉面板与通知列表页共用（移动端通知页复用 PC 实现）
 * - 未读角标只来自未读数接口，操作成功后按结果递减或清零，失败回滚；
 *   列表加载不得重算覆盖角标（未读数可能大于列表条数）
 * - 请求异常提示由统一请求层处理，此处只做状态回滚
 */
import { ref } from 'vue'
import type { Ref } from 'vue'
import { markAllNotificationsRead, markNotificationRead } from '@/api/notification'
import type { NotificationItem } from '@/types/notification'

export interface UseNotificationActionsOptions {
  /** 通知列表，已读状态就地更新 */
  items: Ref<NotificationItem[]>
  /** 未读角标 ref（可选）：传入时随操作同步递减/清零并在失败时回滚 */
  unreadCount?: Ref<number>
}

export function useNotificationActions(options: UseNotificationActionsOptions) {
  const { items, unreadCount } = options
  const marking = ref(false)

  /** 标记单条通知已读，乐观递减角标，失败回滚 */
  async function markRead(item: NotificationItem) {
    if (item.isRead) return
    const previousCount = unreadCount?.value ?? 0
    item.isRead = true
    if (unreadCount) unreadCount.value = Math.max(0, previousCount - 1)
    marking.value = true
    try {
      await markNotificationRead(item.id)
    } catch {
      item.isRead = false
      if (unreadCount) unreadCount.value = previousCount
    } finally {
      marking.value = false
    }
  }

  /** 标记全部通知已读，乐观清零角标，失败回滚列表与角标 */
  async function markAllRead() {
    const hasUnread = items.value.some((item) => !item.isRead)
    if (!hasUnread && (unreadCount?.value ?? 0) === 0) return
    const previousCount = unreadCount?.value ?? 0
    const previousReadState = items.value.map((item) => item.isRead)
    items.value.forEach((item) => {
      item.isRead = true
    })
    if (unreadCount) unreadCount.value = 0
    marking.value = true
    try {
      await markAllNotificationsRead()
    } catch {
      items.value.forEach((item, index) => {
        item.isRead = previousReadState[index]
      })
      if (unreadCount) unreadCount.value = previousCount
    } finally {
      marking.value = false
    }
  }

  return { markRead, markAllRead, marking }
}
