/**
 * 通知相关类型定义
 */

/** SMTP 加密方式：无 / SSL / STARTTLS */
export type SmtpEncryption = 'none' | 'ssl' | 'starttls'

/**
 * 通知事件类型，与后端 NotificationEventType 枚举一一对应
 * （email_share_failed 为邮件分享失败的站内补发事件，不出现在管理端事件开关中）
 */
export type NotificationEventType =
  | 'transfer_completed'
  | 'transfer_failed'
  | 'quota_alert'
  | 'remote_sync_failed'
  | 'media_scan_failed'
  | 'user_registered'
  | 'storage_capacity_alert'
  | 'email_share_failed'

/**
 * 邮件发送记录的事件类型：通知事件之外还包含邮件分享（email_share）与 SMTP 测试邮件（smtp_test）
 */
export type NotificationLogEventType = NotificationEventType | 'email_share' | 'smtp_test'

/**
 * SMTP 表单字段模型，供管理端配置卡片与系统初始化向导共用
 */
export interface SmtpFormModel {
  host: string
  port: number
  username: string
  password: string
  encryption: SmtpEncryption
  fromAddress: string
  fromName: string
}

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
  /** 邮件分享附件直发大小上限（MB） */
  attachmentMaxSizeMb: number
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
  /** 邮件分享附件直发大小上限（MB），为空表示保留原值（默认 50） */
  attachmentMaxSizeMb?: number
}

/**
 * 站内通知项
 */
export interface NotificationItem {
  id: string
  /** 事件类型（对应后端 NotificationEventType 枚举值） */
  eventType: NotificationEventType
  /** 通知标题 */
  title: string
  /** 通知内容 */
  content: string | null
  /** 是否已读 */
  isRead: boolean
  /** 创建时间（GMT+8 yyyy-MM-dd HH:mm:ss） */
  createTime: string
}

/**
 * 通知事件开关项
 */
export interface NotificationEventSwitch {
  /** 事件类型枚举值，如 transfer_completed */
  eventType: NotificationEventType
  /** 事件中文名 */
  name: string
  /** 是否启用 */
  enabled: boolean
}

/**
 * 邮件发送记录项
 */
export interface NotificationSendLog {
  id: string
  /** 事件类型枚举值（含邮件分享 email_share） */
  eventType: NotificationLogEventType
  /** 事件中文名，非通知事件类型时回退为枚举值 */
  eventTypeName: string
  /** 收件邮箱地址 */
  recipient: string
  /** 邮件主题 */
  subject: string
  /** 是否发送成功 */
  success: boolean
  /** 失败原因，成功时为空 */
  errorMessage: string | null
  /** 发送时间（GMT+8 yyyy-MM-dd HH:mm:ss） */
  createTime: string
}

/**
 * 邮件发送记录分页查询参数
 */
export interface NotificationSendLogQuery {
  pageNum?: number
  pageSize?: number
}

