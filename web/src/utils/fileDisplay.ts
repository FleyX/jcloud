import {
  FileText,
  FolderUp,
  Image as ImageIcon,
  Film,
  Music,
} from '@lucide/vue'
import type { Component } from 'vue'

export type FileDisplayType = 'image' | 'video' | 'audio' | 'doc' | 'folder'

export interface FileTypeSource {
  type: 'file' | 'folder'
  name: string
  mimeType?: string
}

export const fileIconMap: Record<FileDisplayType, Component> = {
  doc: FileText,
  image: ImageIcon,
  video: Film,
  audio: Music,
  folder: FolderUp,
}

/**
 * 根据文件名与 MIME 类型推断文件展示类型
 */
export function inferFileType(source: FileTypeSource): FileDisplayType {
  if (source.type === 'folder') return 'folder'

  const mime = source.mimeType || ''
  if (mime.startsWith('image/')) return 'image'
  if (mime.startsWith('video/')) return 'video'
  if (mime.startsWith('audio/')) return 'audio'

  const ext = source.name.split('.').pop()?.toLowerCase() || ''
  if (['png', 'jpg', 'jpeg', 'gif', 'webp', 'svg', 'bmp'].includes(ext)) return 'image'
  if (['mp4', 'mov', 'avi', 'mkv', 'webm'].includes(ext)) return 'video'
  if (['mp3', 'wav', 'flac', 'aac', 'ogg'].includes(ext)) return 'audio'

  return 'doc'
}

/**
 * 将字节数格式化为可读大小
 */
export function formatSize(bytes?: string | number): string {
  const num = Number(bytes)
  if (!num) return '-'

  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  let i = 0
  let size = num
  while (size >= 1024 && i < units.length - 1) {
    size /= 1024
    i++
  }
  return `${size.toFixed(2)} ${units[i]}`
}

/**
 * 从日期时间字符串中提取日期部分
 */
export function formatDate(time?: string): string {
  if (!time) return '-'
  return time.split(' ')[0]
}

/**
 * 根据文件展示类型返回对应的背景/文字色样式
 */
export function getTypeStyle(type: FileDisplayType): string {
  switch (type) {
    case 'image':
      return 'bg-purple-100 text-purple-600'
    case 'video':
      return 'bg-rose-100 text-rose-600'
    case 'audio':
      return 'bg-amber-100 text-amber-600'
    case 'folder':
      return 'bg-emerald-100 text-emerald-600'
    default:
      return 'bg-blue-100 text-blue-600'
  }
}
