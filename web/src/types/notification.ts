/**
 * 通知相关类型定义
 */

/** SMTP 加密方式：无 / SSL / STARTTLS */
export type SmtpEncryption = 'none' | 'ssl' | 'starttls'

/**
 * 发件邮箱（SMTP）配置视图，密码不回显，hasPassword 表示是否已设置密码
 */
export interface SmtpConfig {
  host: string
  port: number
  username: string
  encryption: SmtpEncryption
  fromAddress: string
  fromName: string
  hasPassword: boolean
}

/**
 * 发件邮箱配置保存入参，password 为空表示保留原密码
 */
export interface SmtpConfigPayload {
  host: string
  port: number
  username: string
  password?: string
  encryption: SmtpEncryption
  fromAddress: string
  fromName: string
}

/**
 * 站内通知项
 */
export interface NotificationItem {
  id: string
  /** 事件类型，如 transfer_completed / transfer_failed */
  eventType: string
  /** 通知标题 */
  title: string
  /** 通知内容 */
  content: string | null
  /** 是否已读 */
  isRead: boolean
  /** 创建时间（GMT+8 yyyy-MM-dd HH:mm:ss） */
  createTime: string
}

