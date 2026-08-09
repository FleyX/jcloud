import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ref } from 'vue'
import { subtitleItemKey, useSubtitleSelection } from './useSubtitleSelection'
import { resetPlaybackConfigCache } from './usePlaybackConfig'
import type { MediaSubtitleItem } from '@/types/media'

const mocks = vi.hoisted(() => ({
  subtitleUrl: vi.fn(),
  externalSubtitleUrl: vi.fn(),
}))

vi.mock('@/api/media', () => mocks)

function buildSubtitles(): MediaSubtitleItem[] {
  return [
    { type: 'external', index: null, subtitleId: 'ext-zh', label: '中文', language: 'zh', defaulted: false },
    { type: 'embedded', index: 0, subtitleId: null, label: '英文', language: 'en', defaulted: true },
    { type: 'embedded', index: 1, subtitleId: null, label: '日文', language: 'ja', defaulted: false },
  ]
}

function createSelection() {
  const subtitles = ref<MediaSubtitleItem[]>(buildSubtitles())
  const itemId = ref<string | null>('item-1')
  const currentVersionId = ref<string | null>(null)
  const transcodeActive = ref(false)
  const transcodeBaseMs = ref(0)
  const selection = useSubtitleSelection({
    subtitles,
    itemId,
    currentVersionId,
    transcodeActive,
    transcodeBaseMs,
  })
  return { selection, subtitles, itemId, currentVersionId, transcodeActive, transcodeBaseMs }
}

beforeEach(() => {
  vi.clearAllMocks()
  localStorage.clear()
  resetPlaybackConfigCache()
  mocks.subtitleUrl.mockReturnValue('/sub/embedded')
  mocks.externalSubtitleUrl.mockReturnValue('/sub/external')
})

describe('useSubtitleSelection 默认字幕优先级', () => {
  it('defaulted 标记优先于 localStorage 语言偏好', () => {
    const { selection } = createSelection()
    localStorage.setItem('jcloud.player.subtitlePref', 'zh')

    selection.applyDefaultSubtitle(buildSubtitles())

    // defaulted 英文轨优先，而非偏好语言 zh
    expect(selection.subtitleKey.value).toBe('embedded:0')
  })

  it('无 defaulted 时按 localStorage 语言偏好匹配（language 或 label）', () => {
    const { selection } = createSelection()
    const list = buildSubtitles().map((s) => ({ ...s, defaulted: false }))

    localStorage.setItem('jcloud.player.subtitlePref', 'zh')
    selection.applyDefaultSubtitle(list)
    expect(selection.subtitleKey.value).toBe('external:ext-zh')

    localStorage.setItem('jcloud.player.subtitlePref', '日文')
    selection.applyDefaultSubtitle(list)
    expect(selection.subtitleKey.value).toBe('embedded:1')
  })

  it('无 defaulted 且偏好不匹配或无偏好时字幕为「无」（null）', () => {
    const { selection } = createSelection()
    const list = buildSubtitles().map((s) => ({ ...s, defaulted: false }))

    selection.applyDefaultSubtitle(list)
    expect(selection.subtitleKey.value).toBeNull()

    localStorage.setItem('jcloud.player.subtitlePref', 'fr')
    selection.applyDefaultSubtitle(list)
    expect(selection.subtitleKey.value).toBeNull()
  })
})

describe('useSubtitleSelection activeSubtitle URL 构造', () => {
  it('直放：字幕 URL 不携带转码偏移（offset 0）', () => {
    const { selection } = createSelection()

    selection.selectSubtitle('external:ext-zh')

    expect(selection.activeSubtitle.value).not.toBeNull()
    expect(mocks.externalSubtitleUrl).toHaveBeenCalledWith('item-1', 'ext-zh', undefined, 0)
  })

  it('转码：字幕 URL 携带会话起点偏移，且随 transcodeBaseMs 变化响应联动', () => {
    const { selection, transcodeActive, transcodeBaseMs } = createSelection()
    transcodeActive.value = true
    transcodeBaseMs.value = 60_000
    selection.selectSubtitle('external:ext-zh')

    expect(selection.activeSubtitle.value).not.toBeNull()
    expect(mocks.externalSubtitleUrl).toHaveBeenCalledWith('item-1', 'ext-zh', undefined, 60_000)

    transcodeBaseMs.value = 120_000
    // 先读 activeSubtitle 触发 computed 重算（computed 惰性求值）
    expect(selection.activeSubtitle.value?.src).toBe('/sub/external')
    expect(mocks.externalSubtitleUrl).toHaveBeenCalledWith('item-1', 'ext-zh', undefined, 120_000)
  })

  it('转码：transcodeBaseMs 含小数时 offsetMs 取整（后端为整型，小数会 400）', () => {
    const { selection, transcodeActive, transcodeBaseMs } = createSelection()
    transcodeActive.value = true
    transcodeBaseMs.value = 60_000.9
    selection.selectSubtitle('external:ext-zh')

    expect(selection.activeSubtitle.value).not.toBeNull()
    expect(mocks.externalSubtitleUrl).toHaveBeenCalledWith('item-1', 'ext-zh', undefined, 60_000)
  })

  it('内嵌字幕走 subtitleUrl（index），外置字幕走 externalSubtitleUrl（subtitleId），版本参数透传', () => {
    const { selection, currentVersionId, transcodeActive, transcodeBaseMs } = createSelection()
    currentVersionId.value = 'ver-1'
    transcodeActive.value = true
    transcodeBaseMs.value = 30_000

    selection.selectSubtitle('embedded:0')
    // 先读 activeSubtitle 触发 computed 求值（computed 惰性求值）
    expect(selection.activeSubtitle.value?.key).toBe('embedded:0')
    expect(mocks.subtitleUrl).toHaveBeenCalledWith('item-1', 0, 'ver-1', 30_000)

    selection.selectSubtitle('external:ext-zh')
    expect(selection.activeSubtitle.value?.key).toBe('external:ext-zh')
    expect(mocks.externalSubtitleUrl).toHaveBeenCalledWith('item-1', 'ext-zh', 'ver-1', 30_000)
  })

  it('无条目 id、未匹配 key 或无播放源时 activeSubtitle 为 null', () => {
    const { selection, subtitles, itemId } = createSelection()
    // 无条目 id
    expect(selection.activeSubtitle.value).toBeNull()

    itemId.value = 'item-1'
    // key 未匹配任何字幕项
    selection.selectSubtitle('embedded:9')
    expect(selection.activeSubtitle.value).toBeNull()

    // 既无内嵌 index 也无 subtitleId 的项无法构造 src
    subtitles.value = [
      { type: 'external', index: null, subtitleId: null, label: '无源', language: null, defaulted: false },
    ]
    selection.selectSubtitle(subtitleItemKey(subtitles.value[0]))
    expect(selection.activeSubtitle.value).toBeNull()
  })

  it('handleTrackLoad 将 track 置为 showing', () => {
    const { selection } = createSelection()
    const track = { mode: 'hidden' }
    const event = { target: { track } } as unknown as Event

    selection.handleTrackLoad(event)

    expect(track.mode).toBe('showing')
  })
})

describe('useSubtitleSelection selectSubtitle 记忆写入', () => {
  it('selectSubtitle 写入语言偏好（language 或 label），选「无」不写入且保持不变', () => {
    const { selection } = createSelection()

    selection.selectSubtitle('external:ext-zh')
    expect(selection.subtitleKey.value).toBe('external:ext-zh')
    expect(localStorage.getItem('jcloud.player.subtitlePref')).toBe('zh')

    selection.selectSubtitle('embedded:0')
    expect(localStorage.getItem('jcloud.player.subtitlePref')).toBe('en')

    selection.selectSubtitle(null)
    expect(selection.subtitleKey.value).toBeNull()
    expect(localStorage.getItem('jcloud.player.subtitlePref')).toBe('en')
  })

  it('subtitleItemKey：内嵌 embedded:{index}，外置 external:{subtitleId}', () => {
    expect(subtitleItemKey({ type: 'embedded', index: 2, subtitleId: null, label: 'x', language: null, defaulted: false }))
      .toBe('embedded:2')
    expect(subtitleItemKey({ type: 'external', index: null, subtitleId: 's9', label: 'x', language: null, defaulted: false }))
      .toBe('external:s9')
  })
})
