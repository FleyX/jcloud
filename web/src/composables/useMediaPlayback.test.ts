import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ref } from 'vue'
import { useMediaPlayback } from './useMediaPlayback'
import { resetPlaybackConfigCache } from './usePlaybackConfig'
import type { MediaPlaybackConfigVo, MediaPlaybackInfoVo, MediaSubtitleItem } from '@/types/media'

const mocks = vi.hoisted(() => ({
  fetchPlaybackInfo: vi.fn(),
  fetchPlaybackConfig: vi.fn(),
  createTranscodeSession: vi.fn(),
  subtitleUrl: vi.fn(),
  externalSubtitleUrl: vi.fn(),
  updateMediaProgress: vi.fn().mockResolvedValue(undefined),
  closeTranscodeSession: vi.fn(),
  transcodeHeartbeat: vi.fn(),
  transcodeCloseBeaconUrl: vi.fn(),
}))

vi.mock('@/api/media', () => mocks)

vi.mock('hls.js', () => {
  class MockHls {
    static isSupported() {
      return true
    }

    static Events = { MANIFEST_PARSED: 'manifestParsed', ERROR: 'error' }

    loadSource = vi.fn()
    attachMedia = vi.fn()
    on = vi.fn()
    destroy = vi.fn()
  }
  return { default: MockHls }
})

function createFakeVideo(): HTMLVideoElement {
  const video = {
    src: '',
    currentTime: 0,
    duration: 0,
    buffered: { length: 0, end: () => 0 },
    play: vi.fn().mockResolvedValue(undefined),
    pause: vi.fn(),
    load: vi.fn(),
    removeAttribute: vi.fn(),
    canPlayType: vi.fn(() => ''),
  } as unknown as HTMLVideoElement
  return video
}

function buildPlaybackInfo(overrides: Partial<MediaPlaybackInfoVo> = {}): MediaPlaybackInfoVo {
  return {
    mode: 'direct',
    directUrl: 'https://cdn.test/movie.mkv',
    transcodeUrl: null,
    durationMs: 7_200_000,
    container: 'mkv',
    videoCodec: 'h264',
    audioCodec: 'aac',
    width: 1920,
    height: 1080,
    audioTracks: [{ index: 0, codec: 'aac', language: 'ja', title: null }],
    subtitleTracks: [],
    subtitles: [
      { type: 'external', index: null, subtitleId: 'sub-1', label: '中文', language: 'zh', defaulted: true, bitmap: false },
    ],
    effectiveBitRate: null,
    progressMs: 0,
    ...overrides,
  }
}

function buildPlaybackConfig(): MediaPlaybackConfigVo {
  return {
    bitrateTiers: [
      { key: 'original', label: '原画', kbps: null, maxHeight: null },
      { key: '20000-2160', label: '20M · 4K', kbps: 20000, maxHeight: 2160 },
      { key: '8000-1080', label: '8M · 1080p', kbps: 8000, maxHeight: 1080 },
      { key: '4000-1080', label: '4M · 1080p', kbps: 4000, maxHeight: 1080 },
      { key: '2000-720', label: '2M · 720p', kbps: 2000, maxHeight: 720 },
      { key: '1000-480', label: '1M · 480p', kbps: 1000, maxHeight: 480 },
      { key: '500-360', label: '500K · 360p', kbps: 500, maxHeight: 360 },
    ],
    directPlay: { containers: ['mp4'], videoCodecs: ['h264'], audioCodecs: ['aac'] },
    remux: { videoCopyCodecs: ['h264', 'hevc', 'vp9', 'av1'], audioCopyCodecs: ['aac', 'mp3'] },
    finishedRatio: 0.95,
  }
}

function createPlayback() {
  const video = createFakeVideo()
  const videoRef = ref<HTMLVideoElement | null>(video)
  const pb = useMediaPlayback(videoRef)
  return { video, pb }
}

beforeEach(() => {
  vi.clearAllMocks()
  mocks.fetchPlaybackConfig.mockResolvedValue(buildPlaybackConfig())
  resetPlaybackConfigCache()
  mocks.subtitleUrl.mockReturnValue('/sub/embedded')
  mocks.externalSubtitleUrl.mockReturnValue('/sub/external')
})

describe('useMediaPlayback 消费播放配置（ADR 0024）', () => {
  it('选中限码率档位且实际码率高于档位时，转码会话携带档位码率与分辨率上限', async () => {
    mocks.fetchPlaybackInfo.mockResolvedValue(buildPlaybackInfo({
      mode: 'direct',
      effectiveBitRate: '5000000',
    }))
    mocks.createTranscodeSession.mockResolvedValue({ sessionId: 's1', playlistUrl: '/hls/p.m3u8' })
    const { pb } = createPlayback()

    await pb.start('item-1')
    await pb.selectBitrateTier('2000-720')

    expect(mocks.createTranscodeSession).toHaveBeenCalledWith(
      'item-1', 0,
      expect.objectContaining({ targetBitrateKbps: 2000, maxHeight: 720 }),
      undefined,
    )
    pb.stop()
  })

  it('档位码率不低于实际码率时按原画处理，不触发转码', async () => {
    mocks.fetchPlaybackInfo.mockResolvedValue(buildPlaybackInfo({
      mode: 'direct',
      effectiveBitRate: '100000',
    }))
    const { pb } = createPlayback()

    await pb.start('item-1')
    await pb.selectBitrateTier('2000-720')

    expect(mocks.createTranscodeSession).not.toHaveBeenCalled()
    pb.stop()
  })

  it('进度已达看完阈值（finishedRatio）时从头播放', async () => {
    mocks.fetchPlaybackInfo.mockResolvedValue(buildPlaybackInfo({
      mode: 'direct',
      progressMs: 6_900_000, // > 7_200_000 * 0.95
    }))
    const { video, pb } = createPlayback()

    await pb.start('item-1')

    expect(video.currentTime).toBe(0)
    pb.stop()
  })
})

describe('useMediaPlayback 字幕偏移与播放源代际', () => {
  it('直放播放：字幕 URL 不携带转码偏移（offset 0）', async () => {
    mocks.fetchPlaybackInfo.mockResolvedValue(buildPlaybackInfo({ mode: 'direct' }))
    const { pb } = createPlayback()

    await pb.start('item-1')

    expect(pb.activeSubtitle.value).not.toBeNull()
    expect(mocks.externalSubtitleUrl).toHaveBeenCalledWith('item-1', 'sub-1', undefined, 0)
  })

  it('从头转码：offset 为 0，不发送偏移参数', async () => {
    mocks.fetchPlaybackInfo.mockResolvedValue(buildPlaybackInfo({ mode: 'transcode' }))
    mocks.createTranscodeSession.mockResolvedValue({ sessionId: 's1', playlistUrl: '/hls/p.m3u8' })
    const { pb } = createPlayback()

    await pb.start('item-1', 0)

    expect(pb.transcodeActive.value).toBe(true)
    expect(pb.activeSubtitle.value).not.toBeNull()
    expect(mocks.externalSubtitleUrl).toHaveBeenCalledWith('item-1', 'sub-1', undefined, 0)
    pb.stop()
  })

  it('续播转码：字幕 URL 携带当前会话起点偏移', async () => {
    mocks.fetchPlaybackInfo.mockResolvedValue(buildPlaybackInfo({ mode: 'transcode' }))
    mocks.createTranscodeSession.mockResolvedValue({ sessionId: 's1', playlistUrl: '/hls/p.m3u8' })
    const { pb } = createPlayback()

    await pb.start('item-1', 60_000)

    expect(pb.transcodeBaseMs.value).toBe(60_000)
    expect(pb.activeSubtitle.value).not.toBeNull()
    expect(mocks.externalSubtitleUrl).toHaveBeenCalledWith('item-1', 'sub-1', undefined, 60_000)
    pb.stop()
  })

  it('seek 超出缓冲重建会话：字幕使用新起点偏移，sourceEpoch 递增', async () => {
    mocks.fetchPlaybackInfo.mockResolvedValue(buildPlaybackInfo({ mode: 'transcode' }))
    mocks.createTranscodeSession.mockResolvedValue({ sessionId: 's1', playlistUrl: '/hls/p.m3u8' })
    const { video, pb } = createPlayback()

    await pb.start('item-1', 60_000)
    const epochBefore = pb.sourceEpoch.value
    video.currentTime = 120

    await pb.seekToAbsolute(120)

    expect(pb.transcodeBaseMs.value).toBe(120_000)
    expect(pb.sourceEpoch.value).toBe(epochBefore + 1)
    expect(pb.activeSubtitle.value).not.toBeNull()
    expect(mocks.externalSubtitleUrl).toHaveBeenLastCalledWith('item-1', 'sub-1', undefined, 120_000)
    pb.stop()
  })

  it('切换版本：重建播放源时沿用当前会话起点偏移并保留版本参数', async () => {
    mocks.fetchPlaybackInfo.mockImplementation(async (_id: string, versionId?: string) =>
      buildPlaybackInfo({ mode: 'transcode', versionId: versionId ?? 'ver-1' }))
    mocks.createTranscodeSession.mockResolvedValue({ sessionId: 's1', playlistUrl: '/hls/p.m3u8' })
    const { pb } = createPlayback()

    await pb.start('item-1', 60_000)
    await pb.selectVersion('ver-2')

    expect(pb.activeSubtitle.value).not.toBeNull()
    expect(mocks.externalSubtitleUrl).toHaveBeenLastCalledWith('item-1', 'sub-1', 'ver-2', 60_000)
    pb.stop()
  })
})

describe('useMediaPlayback 位图字幕模式切换', () => {
  const bitmapEmbedded: MediaSubtitleItem = {
    type: 'embedded', index: 2, subtitleId: null, label: '图形字幕', language: null, defaulted: false, bitmap: true,
  }
  const bitmapExternal: MediaSubtitleItem = {
    type: 'external', index: null, subtitleId: 'ext-bmp', label: '图形外字', language: null, defaulted: false, bitmap: true,
  }
  /** 冲刷 selectSubtitle 触发的 fire-and-forget reconcilePlayback 微任务链 */
  const flushPromises = () => new Promise<void>((resolve) => setTimeout(resolve, 0))

  it('直放中选中位图字幕：以当前进度创建烧录转码会话（subtitleIndex）', async () => {
    mocks.fetchPlaybackInfo.mockResolvedValue(buildPlaybackInfo({
      mode: 'direct',
      subtitles: [bitmapEmbedded],
    }))
    mocks.createTranscodeSession.mockResolvedValue({ sessionId: 's1', playlistUrl: '/hls/p.m3u8' })
    const { video, pb } = createPlayback()

    await pb.start('item-1')
    video.currentTime = 60
    pb.selectSubtitle('embedded:2')

    expect(mocks.createTranscodeSession).toHaveBeenCalledWith(
      'item-1', 60_000,
      expect.objectContaining({ subtitleIndex: 2 }),
      undefined,
    )
    pb.stop()
  })

  it('选中外部位图字幕：createTranscodeSession 收到 externalSubtitleId', async () => {
    mocks.fetchPlaybackInfo.mockResolvedValue(buildPlaybackInfo({
      mode: 'direct',
      subtitles: [bitmapExternal],
    }))
    mocks.createTranscodeSession.mockResolvedValue({ sessionId: 's1', playlistUrl: '/hls/p.m3u8' })
    const { pb } = createPlayback()

    await pb.start('item-1')
    pb.selectSubtitle('external:ext-bmp')

    expect(mocks.createTranscodeSession).toHaveBeenCalledWith(
      'item-1', 0,
      expect.objectContaining({ externalSubtitleId: 'ext-bmp' }),
      undefined,
    )
    pb.stop()
  })

  it('位图字幕切回「无」：恢复直放并从当前位置继续', async () => {
    mocks.fetchPlaybackInfo.mockResolvedValue(buildPlaybackInfo({
      mode: 'direct',
      subtitles: [bitmapEmbedded],
    }))
    mocks.createTranscodeSession.mockResolvedValue({ sessionId: 's1', playlistUrl: '/hls/p.m3u8' })
    const { video, pb } = createPlayback()

    await pb.start('item-1')
    video.currentTime = 30
    pb.selectSubtitle('embedded:2')
    await flushPromises()
    expect(pb.transcodeActive.value).toBe(true)
    // 烧录会话从 30s 起点重新起播（流内时间归零），绝对位置 = base(30s) + 流内时间
    video.currentTime = 15 // 从 30s 继续播放 15s → 绝对 45s

    pb.selectSubtitle(null)
    await flushPromises()

    expect(pb.transcodeActive.value).toBe(false)
    expect(pb.transcodeBaseMs.value).toBe(0)
    expect(video.src).toBe('https://cdn.test/movie.mkv')
    expect(video.currentTime).toBe(45)
    expect(mocks.closeTranscodeSession).toHaveBeenCalled()
    pb.stop()
  })

  it('位图字幕切回「无」：原 transcode 文件恢复 track 转码而非直放', async () => {
    mocks.fetchPlaybackInfo.mockResolvedValue(buildPlaybackInfo({
      mode: 'transcode',
      subtitles: [bitmapEmbedded],
    }))
    mocks.createTranscodeSession.mockResolvedValue({ sessionId: 's1', playlistUrl: '/hls/p.m3u8' })
    const { video, pb } = createPlayback()

    await pb.start('item-1', 10_000)
    pb.selectSubtitle('embedded:2')
    await flushPromises()
    expect(pb.transcodeActive.value).toBe(true)

    video.currentTime = 20
    pb.selectSubtitle(null)
    await flushPromises()

    expect(pb.transcodeActive.value).toBe(true)
    expect(mocks.createTranscodeSession).toHaveBeenLastCalledWith(
      'item-1', 30_000,
      expect.anything(),
      undefined,
    )
    pb.stop()
  })

  it('文本轨之间切换不重建播放源', async () => {
    mocks.fetchPlaybackInfo.mockResolvedValue(buildPlaybackInfo({
      mode: 'direct',
      subtitles: [
        { type: 'embedded', index: 0, subtitleId: null, label: '英文', language: 'en', defaulted: false, bitmap: false },
        { type: 'embedded', index: 1, subtitleId: null, label: '日文', language: 'ja', defaulted: false, bitmap: false },
      ],
    }))
    const { pb } = createPlayback()

    await pb.start('item-1')
    const epoch = pb.sourceEpoch.value
    pb.selectSubtitle('embedded:1')

    expect(mocks.createTranscodeSession).not.toHaveBeenCalled()
    expect(pb.sourceEpoch.value).toBe(epoch)
    expect(pb.activeSubtitle.value?.key).toBe('embedded:1')
    pb.stop()
  })
})
