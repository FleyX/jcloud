import { describe, expect, it } from 'vitest'
import { resolvePreviewCategory } from '@/utils/previewCategory'

describe('resolvePreviewCategory', () => {
  it('识别图片', () => {
    expect(resolvePreviewCategory('image/png', 'a.png')).toBe('image')
    expect(resolvePreviewCategory(null, 'a.webp')).toBe('image')
  })

  it('识别视频', () => {
    expect(resolvePreviewCategory('video/mp4', 'a.mp4')).toBe('video')
  })

  it('识别文本', () => {
    expect(resolvePreviewCategory('text/plain', 'a.txt')).toBe('text')
    expect(resolvePreviewCategory(null, 'a.md')).toBe('text')
  })

  it('识别 Office 文档', () => {
    expect(resolvePreviewCategory('application/pdf', 'a.pdf')).toBe('office')
    expect(resolvePreviewCategory(null, 'a.pdf')).toBe('office')
    expect(resolvePreviewCategory(null, 'a.doc')).toBe('office')
    expect(resolvePreviewCategory(null, 'a.docx')).toBe('office')
    expect(resolvePreviewCategory(null, 'a.xls')).toBe('office')
    expect(resolvePreviewCategory(null, 'a.xlsx')).toBe('office')
    expect(resolvePreviewCategory(null, 'a.ppt')).toBe('office')
    expect(resolvePreviewCategory(null, 'a.pptx')).toBe('office')
    // 扩展名大小写不敏感
    expect(resolvePreviewCategory(null, 'a.PDF')).toBe('office')
    expect(resolvePreviewCategory(null, 'a.XLSX')).toBe('office')
  })

  it('不支持的类型', () => {
    expect(resolvePreviewCategory('application/zip', 'a.zip')).toBe('unsupported')
    expect(resolvePreviewCategory(null, 'a.exe')).toBe('unsupported')
  })
})
