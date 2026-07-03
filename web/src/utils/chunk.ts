/**
 * 文件分片工具
 *
 * 与后端 ChunkedUploadService 约定一致：
 * - 默认分片大小 64MB
 * - 每个分片使用完整 MD5 作为 chunkHash
 */

import { md5 } from 'js-md5'

/**
 * 默认分片大小：64MB。
 * 实际以上传初始化接口返回的 chunkSize 为准，此处仅作为兜底默认值。
 */
export const CHUNK_SIZE = 64 * 1024 * 1024

/**
 * 分片信息。
 */
export interface ChunkInfo {
  index: number
  blob: Blob
  size: number
  hash: string
}

/**
 * 将文件切分为指定大小的分片，并计算每个分片的 MD5。
 *
 * @param file 待切分文件
 * @param chunkSize 分片大小，默认 64MB
 * @returns 分片信息列表
 */
export async function createChunks(file: File, chunkSize = CHUNK_SIZE): Promise<ChunkInfo[]> {
  const chunks: ChunkInfo[] = []
  let start = 0
  let index = 0

  while (start < file.size) {
    const end = Math.min(start + chunkSize, file.size)
    const blob = file.slice(start, end)
    const hash = await computeChunkHash(blob)
    chunks.push({
      index,
      blob,
      size: blob.size,
      hash,
    })
    start = end
    index++
  }

  return chunks
}

/**
 * 计算单个 Blob 的 MD5。
 *
 * @param blob 待计算 Blob
 * @returns MD5 字符串
 */
export async function computeChunkHash(blob: Blob): Promise<string> {
  const buffer = await blob.arrayBuffer()
  return md5(buffer)
}

/**
 * 计算文件总分片数。
 *
 * @param fileSize 文件大小
 * @param chunkSize 分片大小
 * @returns 分片数
 */
export function calculateTotalChunks(fileSize: number, chunkSize = CHUNK_SIZE): number {
  if (fileSize <= 0) return 0
  return Math.ceil(fileSize / chunkSize)
}
