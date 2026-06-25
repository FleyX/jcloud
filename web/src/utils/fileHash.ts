import { md5 } from 'js-md5'

const THRESHOLD = 150 * 1024 * 1024

/**
 * 计算文件身份 hash，与后端 FileHashUtil.identityHash 保持一致。
 * - 小于 150MB：完整文件 MD5
 * - 大于等于 150MB：前 50MB + 中间 50MB + 后 50MB 拼接后 MD5
 *
 * 当前浏览器端仅完整实现小文件场景；大文件直接返回 null，由调用方退化到普通上传。
 */
export async function identityHash(file: File): Promise<string | null> {
  if (file.size < THRESHOLD) {
    const buffer = await file.arrayBuffer()
    return md5(buffer)
  }
  return null
}

/**
 * 计算文件完整 MD5。
 */
export async function fullHash(file: File): Promise<string> {
  const buffer = await file.arrayBuffer()
  return md5(buffer)
}
