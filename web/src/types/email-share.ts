/**
 * 邮件分享（链接分享模式）相关类型定义
 */

/**
 * 链接分享邮件发送请求
 */
export interface EmailShareLinkRequest {
  /** 分享短码 */
  shareCode: string
  /** 完整分享链接（由前端用站点 origin 组装） */
  shareUrl: string
  /** 分享名称，写入邮件正文 */
  shareName: string
  /** 访问密码明文，为空表示分享无密码 */
  password?: string
  /** 收件邮箱列表，1~20 个 */
  recipients: string[]
}

/**
 * 附件直发邮件发送请求
 */
export interface EmailShareAttachmentRequest {
  /** 文件节点 ID 列表，仅支持文件类型节点 */
  fileNodeIds: string[]
  /** 收件邮箱列表，1~20 个 */
  recipients: string[]
}

/**
 * 最近收件人与发件邮箱可用状态
 */
export interface RecentRecipientsVo {
  /** 是否已配置发件邮箱，false 时前端展示引导并禁用提交 */
  smtpConfigured: boolean
  /** 最近收件邮箱，最多 5 个、最新在前 */
  recipients: string[]
  /** 附件直发大小上限（MB），供弹窗预检提示 */
  attachmentMaxSizeMb: number
}
