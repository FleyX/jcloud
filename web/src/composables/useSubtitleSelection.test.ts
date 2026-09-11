import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ref } from 'vue'
import { subtitleItemKey, useSubtitleSelection } from './useSubtitleSelection'
import { resetPlaybackConfigCache } from './usePlaybackConfig'
import type { MediaSubtitleItem } from '@/types/media'

const mocks = vi.hoisted(() => ({
  subtitleUrl: vi.fn(),
  externalSubtitleUrl: vi.fn(),
  pureSubtitleUrl: vi.fn(),
  pureExternalSubtitleUrl: vi.fn(),
}))

vi.mock('@/api/media', () => mocks)

function buildSubtitles(): MediaSubtitleItem[] {
  return [
    { type: 'external', index: null, subtitleId: 'ext-zh', label: '中文', language: 'zh', defaulted: false, bitmap: false },
    { type: 'embedded', index: 0, subtitleId: null, label: '英文', language: 'en', defaulted: true, bitmap: false },
    { type: 'embedded', index: 1, subtitleId: null, label: '日文', language: 'ja', defaulted: false, bitmap: false },
  ]
}

function createSelection(options: { pure?: boolean } = {}) {
  const subtitles = ref<MediaSubtitleItem[]>(buildSubtitles())
  const itemId = ref<string | null>('item-1')
  const currentVersionId = ref<string | null>(null)
  const transcodeActive = ref(false)
  const transcodeBaseMs = ref(0)
  const pure = ref(options.pure ?? false)
  const selection = useSubtitleSelection({
    subtitles,
    itemId,
    currentVersionId,
    transcodeActive,
    transcodeBaseMs,
    pure,
  })
  return { selection, subtitles, itemId, currentVersionId, transcodeActive, transcodeBaseMs, pure }
}

beforeEach(() => {
  vi.clearAllMocks()
  localStorage.clear()
  resetPlaybackConfigCache()
  mocks.subtitleUrl.mockReturnValue('/sub/embedded')
  mocks.externalSubtitleUrl.mockReturnValue('/sub/external')
  mocks.pureSubtitleUrl.mockReturnValue('/sub/pure-embedded')
  mocks.pureExternalSubtitleUrl.mockReturnValue('/sub/pure-external')
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

  it('纯播放（pure）：内嵌/外挂字幕 URL 走 files 形态，不带版本参数，offsetMs 透传', () => {
    const { selection, transcodeActive, transcodeBaseMs } = createSelection({ pure: true })
    transcodeActive.value = true
    transcodeBaseMs.value = 45_000

    selection.selectSubtitle('embedded:0')
    expect(selection.activeSubtitle.value?.key).toBe('embedded:0')
    expect(mocks.pureSubtitleUrl).toHaveBeenCalledWith('item-1', 0, 45_000)
    expect(mocks.subtitleUrl).not.toHaveBeenCalled()

    selection.selectSubtitle('external:ext-zh')
    expect(selection.activeSubtitle.value?.key).toBe('external:ext-zh')
    expect(mocks.pureExternalSubtitleUrl).toHaveBeenCalledWith('item-1', 'ext-zh', 45_000)
    expect(mocks.externalSubtitleUrl).not.toHaveBeenCalled()
  })

  it('纯播放（pure）：直放时 offsetMs 为 0', () => {
    const { selection } = createSelection({ pure: true })

    selection.selectSubtitle('external:ext-zh')

    expect(selection.activeSubtitle.value).not.toBeNull()
    expect(mocks.pureExternalSubtitleUrl).toHaveBeenCalledWith('item-1', 'ext-zh', 0)
  })

  it('非纯播放（pure=false）不触碰纯播放 URL 函数', () => {
    const { selection } = createSelection()

    selection.selectSubtitle('embedded:0')
    expect(selection.activeSubtitle.value?.key).toBe('embedded:0')
    expect(mocks.subtitleUrl).toHaveBeenCalledWith('item-1', 0, undefined, 0)
    expect(mocks.pureSubtitleUrl).not.toHaveBeenCalled()
    expect(mocks.pureExternalSubtitleUrl).not.toHaveBeenCalled()
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
      { type: 'external', index: null, subtitleId: null, label: '无源', language: null, defaulted: false, bitmap: false },
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
    expect(subtitleItemKey({ type: 'embedded', index: 2, subtitleId: null, label: 'x', language: null, defaulted: false, bitmap: false }))
      .toBe('embedded:2')
    expect(subtitleItemKey({ type: 'external', index: null, subtitleId: 's9', label: 'x', language: null, defaulted: false, bitmap: false }))
      .toBe('external:s9')
  })
})

describe('useSubtitleSelection 位图字幕', () => {
  const bitmapEmbedded: MediaSubtitleItem = {
    type: 'embedded', index: 2, subtitleId: null, label: '图形字幕', language: 'zh', defaulted: true, bitmap: true,
  }
  const bitmapExternal: MediaSubtitleItem = {
    type: 'external', index: null, subtitleId: 'ext-bmp', label: '图形外字', language: 'de', defaulted: false, bitmap: true,
  }

  it('applyDefaultSubtitle 跳过位图项：defaulted 位图轨即使带标记也不自动选中', () => {
    const { selection } = createSelection()
    const list = [bitmapEmbedded, ...buildSubtitles()]

    selection.applyDefaultSubtitle(list)

    // 跳过 defaulted 位图轨，选中文本 defaulted 英文轨
    expect(selection.subtitleKey.value).toBe('embedded:0')

    // 仅剩位图项时（defaulted 且偏好均命中位图）回退为「无」
    localStorage.setItem('jcloud.player.subtitlePref', 'zh')
    selection.applyDefaultSubtitle([bitmapEmbedded])
    expect(selection.subtitleKey.value).toBeNull()
  })

  it('applyDefaultSubtitle 偏好匹配跳过位图项：偏好命中位图项时回退为「无」', () => {
    const { selection } = createSelection()
    const list = [bitmapExternal, ...buildSubtitles().map((s) => ({ ...s, defaulted: false }))]

    // 偏好 zh 命中文本中文轨（而非同样为 zh 的位图轨）
    localStorage.setItem('jcloud.player.subtitlePref', 'zh')
    selection.applyDefaultSubtitle(list)
    expect(selection.subtitleKey.value).toBe('external:ext-zh')

    // 偏好 de 仅命中位图轨 → 不选中，回退为「无」
    localStorage.setItem('jcloud.player.subtitlePref', 'de')
    selection.applyDefaultSubtitle(list)
    expect(selection.subtitleKey.value).toBeNull()
  })

  it('selectSubtitle 位图项不写入语言偏好', () => {
    const { selection, subtitles } = createSelection()
    subtitles.value = [bitmapEmbedded, ...buildSubtitles()]

    selection.selectSubtitle(subtitleItemKey(bitmapEmbedded))
    expect(selection.subtitleKey.value).toBe('embedded:2')
    expect(localStorage.getItem('jcloud.player.subtitlePref')).toBeNull()

    // 切回文本轨正常记忆
    selection.selectSubtitle('embedded:0')
    expect(localStorage.getItem('jcloud.player.subtitlePref')).toBe('en')
  })

  it('activeSubtitle 对位图项返回 null（不渲染 track，不请求字幕 URL）', () => {
    const { selection, subtitles, itemId } = createSelection()
    itemId.value = 'item-1'
    subtitles.value = [bitmapEmbedded]

    selection.selectSubtitle('embedded:2')
    expect(selection.subtitleKey.value).toBe('embedded:2')
    expect(selection.activeSubtitle.value).toBeNull()
    expect(mocks.subtitleUrl).not.toHaveBeenCalled()
  })

  it('burnInSubtitle：内嵌位图返回 subtitleIndex，外部位图返回 externalSubtitleId，文本/「无」为 null', () => {
    const { selection, subtitles } = createSelection()
    subtitles.value = [bitmapEmbedded, bitmapExternal, ...buildSubtitles()]

    selection.selectSubtitle('embedded:2')
    expect(selection.burnInSubtitle.value).toEqual({ subtitleIndex: 2 })

    selection.selectSubtitle('external:ext-bmp')
    expect(selection.burnInSubtitle.value).toEqual({ externalSubtitleId: 'ext-bmp' })

    // 文本项与「无」均不产生烧录参数
    selection.selectSubtitle('embedded:0')
    expect(selection.burnInSubtitle.value).toBeNull()
    selection.selectSubtitle(null)
    expect(selection.burnInSubtitle.value).toBeNull()
  })
})
