import { beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('./request', () => ({
  del: vi.fn(),
  get: vi.fn(),
  post: vi.fn(),
  put: vi.fn(),
}))

import { externalSubtitleUrl, subtitleUrl } from './media'

beforeEach(() => {
  localStorage.setItem('jcloud_token', 'test-token')
})

describe('字幕资源 URL 构造', () => {
  it('直放（无 versionId、offsetMs=0）不发送偏移参数', () => {
    const url = subtitleUrl('item-1', 0)
    expect(url).toContain('/jcloud/api/media/items/item-1/subtitles/0')
    expect(url).not.toContain('offsetMs')
    expect(url).toContain('token=test-token')
  })

  it('offsetMs 为 0 时不发送偏移参数，即使显式传入 0', () => {
    const url = externalSubtitleUrl('item-1', 'sub-1', undefined, 0)
    expect(url).not.toContain('offsetMs')
    expect(url).toContain('/subtitles/external/sub-1')
  })

  it('转码时携带 offsetMs', () => {
    const url = subtitleUrl('item-1', 0, undefined, 60_000)
    expect(url).toContain('offsetMs=60000')
    expect(url).toContain('token=test-token')
  })

  it('versionId 与 offsetMs 并存时不覆盖或丢失版本参数', () => {
    const embedded = subtitleUrl('item-1', 0, 'ver-9', 60_000)
    expect(embedded).toContain('versionId=ver-9')
    expect(embedded).toContain('offsetMs=60000')

    const external = externalSubtitleUrl('item-1', 'sub-1', 'ver-9', 60_000)
    expect(external).toContain('versionId=ver-9')
    expect(external).toContain('offsetMs=60000')
  })
})
