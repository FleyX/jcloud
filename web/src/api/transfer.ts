import { get, post } from './request'
import type { TransferCreateRequest, TransferTaskVo } from '@/types/transfer'

/**
 * 创建跨来源移动任务。
 */
export function createTransferMove(dto: TransferCreateRequest): Promise<TransferTaskVo> {
  return post<TransferTaskVo>('/transfers/move', dto)
}

/**
 * 创建跨来源复制任务。
 */
export function createTransferCopy(dto: TransferCreateRequest): Promise<TransferTaskVo> {
  return post<TransferTaskVo>('/transfers/copy', dto)
}

/**
 * 查询最近的传输任务（含进行中）。
 */
export function fetchRecentTransfers(): Promise<TransferTaskVo[]> {
  return get<TransferTaskVo[]>('/transfers/recent')
}

/**
 * 取消传输任务。
 */
export function cancelTransfer(id: string): Promise<void> {
  return post<void>(`/transfers/${id}/cancel`)
}
