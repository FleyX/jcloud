/**
 * 文件预览分类：根据 MIME 与扩展名决定使用哪种预览渲染方式。
 */
export type PreviewCategory = 'image' | 'video' | 'text' | 'office' | 'unsupported'

const IMAGE_EXTENSIONS = ['png', 'jpg', 'jpeg', 'gif', 'webp', 'svg', 'bmp']
const VIDEO_EXTENSIONS = ['mp4', 'mov', 'avi', 'mkv', 'webm']
const TEXT_EXTENSIONS = ['txt', 'md', 'json', 'xml', 'csv', 'log']
const OFFICE_EXTENSIONS = ['pdf', 'doc', 'docx', 'xls', 'xlsx', 'ppt', 'pptx']

/**
 * 解析文件的预览分类。
 *
 * @param mimeType 文件 MIME 类型，可为空
 * @param fileName 文件名（用于提取扩展名）
 */
export function resolvePreviewCategory(mimeType: string | null | undefined, fileName: string): PreviewCategory {
  const mime = mimeType || ''
  const ext = fileName.split('.').pop()?.toLowerCase() || ''
  if (mime.startsWith('image/') || IMAGE_EXTENSIONS.includes(ext)) {
    return 'image'
  }
  if (mime.startsWith('video/') || VIDEO_EXTENSIONS.includes(ext)) {
    return 'video'
  }
  if (mime.startsWith('text/') || TEXT_EXTENSIONS.includes(ext)) {
    return 'text'
  }
  if (mime === 'application/pdf' || OFFICE_EXTENSIONS.includes(ext)) {
    return 'office'
  }
  return 'unsupported'
}
