import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ref } from 'vue'
import { useTranscodeSession } from './useTranscodeSession'
import { resetPlaybackConfigCache } from './usePlaybackConfig'
import type { MediaPlaybackInfoVo } from '@/types/media'

const mocks = vi.hoisted(() => ({
  createTranscodeSession: vi.fn(),
  closeTranscodeSession: vi.fn(),
  transcodeHeartbeat: vi.fn(),
  transcodeCloseBeaconUrl: vi.fn(),
  withToken: vi.fn((url: string) => url),
}))

vi.mock('@/api/media', () => mocks)

const hlsMock = vi.hoisted(() => ({
  instances: [] as Array<{ destroy: () => void }>,
  isSupported: vi.fn(() => true),
}))

vi.mock('hls.js', () => {
  class MockHls {
    static isSupported = hlsMock.isSupported
    static Events = { MANIFEST_PARSED: 'manifestParsed', ERROR: 'error' }
    loadSource = vi.fn()
    attachMedia = vi.fn()
    on = vi.fn()
    destroy = vi.fn()
    constructor() {
      hlsMock.instances.push(this)
    }
  }
  return { default: MockHls }
})

function createFakeVideo(): HTMLVideoElement {
  const video = {
    src: '',
    currentTime: 0,
    duration: 0,
    // 各测试按需改写 buffered（{ length, end }），end 仅读取，闭包内改值即可
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
    mode: 'transcode',
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
    subtitles: [],
    effectiveBitRate: null,
    progressMs: 0,
    ...overrides,
  }
}

/** 测试内改写 video.buffered（HTMLVideoElement.buffered 为只读 getter，走 unknown 中间态） */
function setBuffered(video: HTMLVideoElement, buffered: { length: number; end: () => number }) {
  ;(video as unknown as { buffered: { length: number; end: () => number } }).buffered = buffered
}

function createSession(overrides: Partial<MediaPlaybackInfoVo> = {}) {
  const videoRef = ref<HTMLVideoElement | null>(createFakeVideo())
  const playbackInfo = ref<MediaPlaybackInfoVo | null>(buildPlaybackInfo(overrides))
  const itemId = ref<string | null>('item-1')
  const currentVersionId = ref<string | null>(null)
  const audioIndex = ref<number | null>(null)
  const errorMsg = ref('')
  const sourceEpoch = ref(0)
  const destroyed = ref(false)
  const session = useTranscodeSession({
    videoRef,
    playbackInfo,
    itemId,
    currentVersionId,
    audioIndex,
    errorMsg,
    sourceEpoch,
    destroyed,
  })
  return { session, videoRef, playbackInfo, itemId, currentVersionId, audioIndex, errorMsg, sourceEpoch, destroyed }
}

beforeEach(() => {
  vi.clearAllMocks()
  hlsMock.instances.length = 0
  localStorage.clear()
  resetPlaybackConfigCache()
  mocks.createTranscodeSession.mockResolvedValue({ sessionId: 's1', playlistUrl: '/hls/p.m3u8' })
  mocks.transcodeCloseBeaconUrl.mockReturnValue('/beacon/close')
})

afterEach(() => {
  vi.useRealTimers()
  vi.unstubAllGlobals()
})

describe('useTranscodeSession 心跳与会话生命周期', () => {
  it('转码会话建立后每 5s 心跳一次，destroyHls 停止心跳并关闭会话', async () => {
    vi.useFakeTimers()
    const { session } = createSession()

    await session.setupTranscode(0)

    expect(mocks.transcodeHeartbeat).not.toHaveBeenCalled()
    vi.advanceTimersByTime(5_000)
    expect(mocks.transcodeHeartbeat).toHaveBeenCalledTimes(1)
    expect(mocks.transcodeHeartbeat).toHaveBeenCalledWith('s1')
    vi.advanceTimersByTime(10_000)
    expect(mocks.transcodeHeartbeat).toHaveBeenCalledTimes(3)

    session.destroyHls()
    expect(mocks.closeTranscodeSession).toHaveBeenCalledWith('s1')
    vi.advanceTimersByTime(10_000)
    expect(mocks.transcodeHeartbeat).toHaveBeenCalledTimes(3)
    session.dispose()
  })

  it('destroyHls 即销毁 HLS 实例并关闭当前转码会话，重复销毁不重复关闭', async () => {
    const { session } = createSession()

    await session.setupTranscode(0)

    expect(hlsMock.instances).toHaveLength(1)
    session.destroyHls()
    expect(hlsMock.instances[0].destroy).toHaveBeenCalledTimes(1)
    expect(mocks.closeTranscodeSession).toHaveBeenCalledWith('s1')

    session.destroyHls()
    expect(mocks.closeTranscodeSession).toHaveBeenCalledTimes(1)
    session.dispose()
  })

  it('页面卸载 pagehide 时用 sendBeacon 关闭当前转码会话，会话置空后不再发送', async () => {
    const sendBeacon = vi.fn()
    vi.stubGlobal('navigator', { sendBeacon })
    const { session } = createSession()

    await session.setupTranscode(0)

    window.dispatchEvent(new Event('pagehide'))
    expect(mocks.transcodeCloseBeaconUrl).toHaveBeenCalledWith('s1')
    expect(sendBeacon).toHaveBeenCalledWith('/beacon/close')

    sendBeacon.mockClear()
    mocks.transcodeCloseBeaconUrl.mockClear()
    window.dispatchEvent(new Event('pagehide'))
    expect(sendBeacon).not.toHaveBeenCalled()
    session.dispose()
  })

  it('会话建立期间 stop（destroyed）则不再挂载，不开启心跳', async () => {
    vi.useFakeTimers()
    const { session, destroyed } = createSession()

    const pending = session.setupTranscode(0)
    destroyed.value = true
    await pending

    expect(mocks.transcodeHeartbeat).not.toHaveBeenCalled()
    expect(session.transcodeActive.value).toBe(false)
    session.dispose()
  })
})

describe('useTranscodeSession seek 重建与码率档位', () => {
  it('seek 超出缓冲 +5s 时销毁会话并从目标位置重建，缓冲内不重建', async () => {
    const { session, videoRef } = createSession()
    await session.setupTranscode(60_000)

    const video = videoRef.value!
    // 缓冲 0..100s：目标 120s 超缓冲 → 销毁旧会话并从绝对 180s 重建
    setBuffered(video, { length: 1, end: () => 100 })
    video.currentTime = 120
    await session.handleSeeking()

    expect(mocks.createTranscodeSession).toHaveBeenCalledTimes(2)
    expect(mocks.createTranscodeSession).toHaveBeenLastCalledWith(
      'item-1', 180_000,
      expect.objectContaining({ audioIndex: undefined }),
      undefined,
    )

    // 缓冲 0..200s：目标 150s 在缓冲内 → 不重建
    setBuffered(video, { length: 1, end: () => 200 })
    video.currentTime = 150
    await session.handleSeeking()
    expect(mocks.createTranscodeSession).toHaveBeenCalledTimes(2)
    session.dispose()
  })

  it('seekToAbsolute 目标在缓冲内直接设置流内时间，超出则重建转码会话', async () => {
    const { session, videoRef } = createSession()
    await session.setupTranscode(60_000)

    const video = videoRef.value!
    setBuffered(video, { length: 1, end: () => 200 })
    await session.seekToAbsolute(120)
    // 流内目标 = 120 - 60 = 60s ≤ 缓冲终点 200s → 直接设置 currentTime
    expect(video.currentTime).toBe(60)

    await session.seekToAbsolute(300)
    // 流内目标 = 240s > 缓冲终点 200s → 重建
    expect(mocks.createTranscodeSession).toHaveBeenLastCalledWith('item-1', 300_000, expect.anything(), undefined)
    session.dispose()
  })

  it('selectBitrateTier 持久化档位记忆并返回是否发生切换', () => {
    const { session, playbackInfo } = createSession()
    playbackInfo.value = buildPlaybackInfo()

    expect(session.selectBitrateTier('2000-720')).toBe(true)
    expect(localStorage.getItem('jcloud.player.bitrateTier')).toBe('2000-720')
    expect(session.bitrateTierKey.value).toBe('2000-720')
    // 相同档位不重复切换
    expect(session.selectBitrateTier('2000-720')).toBe(false)
    // 无播放信息时拒绝切换
    playbackInfo.value = null
    expect(session.selectBitrateTier('original')).toBe(false)
    session.dispose()
  })
})
