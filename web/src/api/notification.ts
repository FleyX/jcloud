import { get, post, put } from './request'
import type { PageResult } from '@/types/auth'
import type {
  NotificationEventSwitch,
  NotificationItem,
  NotificationSendLog,
  NotificationSendLogQuery,
  SmtpConfig,
  SmtpConfigPayload,
} from '@/types/notification'

/**
 * 查询发件邮箱（SMTP）配置，密码不回显
 */
export function getSmtpConfig(): Promise<SmtpConfig> {
  return get<SmtpConfig>('/admin/notification/smtp-config')
}

/**
 * 保存发件邮箱配置，password 为空表示保留原密码
 */
export function updateSmtpConfig(payload: SmtpConfigPayload): Promise<void> {
  return put<void>('/admin/notification/smtp-config', payload)
}

/**
 * 发送测试邮件
 */
export function sendTestMail(to: string): Promise<void> {
  return post<void>('/admin/notification/smtp-config/test', { to })
}

/**
 * 查询七类通知事件的启用状态
 */
export function fetchNotificationEventSwitches(): Promise<NotificationEventSwitch[]> {
  return get<NotificationEventSwitch[]>('/admin/notification/event-switches')
}

/**
 * 设置单个通知事件的启用状态
 */
export function updateNotificationEventSwitch(eventType: string, enabled: boolean): Promise<void> {
  return put<void>(`/admin/notification/event-switches/${eventType}`, { enabled })
}

/**
 * 分页查询邮件发送记录（按发送时间倒序）
 */
export function fetchNotificationSendLogs(params: NotificationSendLogQuery): Promise<PageResult<NotificationSendLog>> {
  return get<PageResult<NotificationSendLog>>('/admin/notification/send-logs', params as Record<string, unknown>)
}

/**
 * 查询当前用户最近通知（倒序，最多 50 条）
 */
export function listNotifications(): Promise<NotificationItem[]> {
  return get<NotificationItem[]>('/notifications')
}

/**
 * 查询当前用户未读通知数（后端 Long 序列化为 string）
 */
export function getUnreadCount(): Promise<string> {
  return get<string>('/notifications/unread-count')
}

/**
 * 标记单条通知已读
 */
export function markNotificationRead(id: string): Promise<void> {
  return put<void>(`/notifications/${id}/read`)
}

/**
 * 标记当前用户全部通知已读
 */
export function markAllNotificationsRead(): Promise<void> {
  return put<void>('/notifications/read-all')
}

