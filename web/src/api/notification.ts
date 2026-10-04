import { get, post, put } from './request'
import type { SmtpConfig, SmtpConfigPayload } from '@/types/notification'

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
