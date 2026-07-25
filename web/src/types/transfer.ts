/**
 * 跨来源传输任务类型定义。
 */

/** 传输任务状态 */
export type TransferTaskStatus =
  | 'PENDING'
  | 'RUNNING'
  | 'CANCELLING'
  | 'CANCELED'
  | 'COMPLETED'
  | 'FAILED'
  | 'PARTIAL'

/** 跨来源传输任务 */
export interface TransferTaskVo {
  id: string
  /** 操作类型 */
  opType: 'copy' | 'move'
  /** 来源类型 */
  sourceType: 'local' | 'remote'
  /** 目标类型 */
  targetType: 'local' | 'remote'
  status: TransferTaskStatus
  /** 待传输文件总数（long 序列化为 string） */
  totalCount: string
  /** 成功数 */
  successCount: string
  /** 失败/跳过数 */
  failCount: string
  /** 总字节数 */
  totalBytes: string
  /** 失败明细 JSON */
  failDetail?: string
  /** 任务级错误信息 */
  errorMsg?: string
  startTime?: string
  endTime?: string
  createTime: string
}

/** 创建传输任务请求项 */
export interface TransferCreateItem {
  id: string
  strategy?: string
}

/** 创建传输任务请求 */
export interface TransferCreateRequest {
  targetParentId: string
  items: TransferCreateItem[]
}
