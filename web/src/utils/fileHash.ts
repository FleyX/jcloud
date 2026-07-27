import { md5 } from 'js-md5'

const THRESHOLD = 150 * 1024 * 1024
const SAMPLE_SIZE = 50 * 1024 * 1024

/**
 * 计算文件身份 hash，与后端 FileHashUtil.identityHash 保持一致。
 * - 小于 150MB：完整文件 MD5
 * - 大于等于 150MB：前 50MB + 中间 50MB（从 size/2 开始）+ 后 50MB 拼接后 MD5
 */
export async function identityHash(file: File): Promise<string> {
  if (file.size < THRESHOLD) {
    const buffer = await file.arrayBuffer()
    return md5(buffer)
  }
  const hash = md5.create()
  const positions = [0, Math.floor(file.size / 2), file.size - SAMPLE_SIZE]
  for (const position of positions) {
    const buffer = await file.slice(position, position + SAMPLE_SIZE).arrayBuffer()
    hash.update(buffer)
  }
  return hash.hex()
}

/**
 * 计算文件完整 MD5。
 */
export async function fullHash(file: File): Promise<string> {
  const buffer = await file.arrayBuffer()
  return md5(buffer)
}
