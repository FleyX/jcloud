import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ref } from 'vue'
import { useMediaPlayback } from './useMediaPlayback'
import type { MediaPlaybackInfoVo } from '@/types/media'

const mocks = vi.hoisted(() => ({
  fetchPlaybackInfo: vi.fn(),
  createTranscodeSession: vi.fn(),
  subtitleUrl: vi.fn(),
  externalSubtitleUrl: vi.fn(),
  withToken: vi.fn((url: string) => url),
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
      { type: 'external', index: null, subtitleId: 'sub-1', label: '中文', language: 'zh', defaulted: true },
    ],
    effectiveBitRate: null,
    progressMs: 0,
    ...overrides,
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
  mocks.subtitleUrl.mockReturnValue('/sub/embedded')
  mocks.externalSubtitleUrl.mockReturnValue('/sub/external')
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
