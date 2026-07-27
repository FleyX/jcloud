/**
 * 文件分片工具
 *
 * 与后端 ChunkedUploadService 约定一致：
 * - 默认分片大小 64MB
 * - 分片仅做切片，不计算 hash（局域网场景信任 TCP 完整性，后端对空 hash 跳过校验）
 */

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
}

/**
 * 将文件切分为指定大小的分片。
 *
 * @param file 待切分文件
 * @param chunkSize 分片大小，默认 64MB
 * @returns 分片信息列表
 */
export function createChunks(file: File, chunkSize = CHUNK_SIZE): ChunkInfo[] {
  const chunks: ChunkInfo[] = []
  let start = 0
  let index = 0

  while (start < file.size) {
    const end = Math.min(start + chunkSize, file.size)
    const blob = file.slice(start, end)
    chunks.push({
      index,
      blob,
      size: blob.size,
    })
    start = end
    index++
  }

  return chunks
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
