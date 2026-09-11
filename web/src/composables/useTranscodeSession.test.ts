import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ref } from 'vue'
import { useTranscodeSession } from './useTranscodeSession'
import { loadPlaybackConfig, resetPlaybackConfigCache } from './usePlaybackConfig'
import type { BurnInParams } from './useSubtitleSelection'
import type { MediaPlaybackConfigVo, MediaPlaybackInfoVo } from '@/types/media'

const mocks = vi.hoisted(() => ({
  createTranscodeSession: vi.fn(),
  createTranscodeSessionByFileNode: vi.fn(),
  closeTranscodeSession: vi.fn(),
  transcodeHeartbeat: vi.fn(),
  transcodeCloseBeaconUrl: vi.fn(),
  fetchPlaybackConfig: vi.fn(),
}))

vi.mock('@/api/media', () => mocks)

const hlsMock = vi.hoisted(() => ({
  instances: [] as Array<{ destroy: () => void }>,
  constructorOptions: [] as Array<Record<string, unknown>>,
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
    constructor(options?: Record<string, unknown>) {
      hlsMock.instances.push(this)
      hlsMock.constructorOptions.push(options ?? {})
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

function createSession(
  overrides: Partial<MediaPlaybackInfoVo> = {},
  depsOverrides: { pure?: boolean; getBurnInParams?: () => BurnInParams | null } = {},
) {
  const videoRef = ref<HTMLVideoElement | null>(createFakeVideo())
  const playbackInfo = ref<MediaPlaybackInfoVo | null>(buildPlaybackInfo(overrides))
  const itemId = ref<string | null>('item-1')
  const currentVersionId = ref<string | null>(null)
  const audioIndex = ref<number | null>(null)
  const errorMsg = ref('')
  const sourceEpoch = ref(0)
  const destroyed = ref(false)
  const pure = ref(depsOverrides.pure ?? false)
  const session = useTranscodeSession({
    videoRef,
    playbackInfo,
    itemId,
    currentVersionId,
    audioIndex,
    errorMsg,
    sourceEpoch,
    destroyed,
    pure,
    getBurnInParams: depsOverrides.getBurnInParams ?? (() => null),
  })
  return { session, videoRef, playbackInfo, itemId, currentVersionId, audioIndex, errorMsg, sourceEpoch, destroyed, pure }
}

function buildPlaybackConfig(): MediaPlaybackConfigVo {
  return {
    bitrateTiers: [
      { key: 'original', label: '原画', kbps: null, maxHeight: null },
      { key: '2000-720', label: '2M · 720p', kbps: 2000, maxHeight: 720 },
    ],
    directPlay: { containers: ['mp4'], videoCodecs: ['h264'], audioCodecs: ['aac'] },
    remux: { videoCopyCodecs: ['h264', 'hevc'], audioCopyCodecs: ['aac', 'mp3'] },
    finishedRatio: 0.95,
  }
}

beforeEach(() => {
  vi.clearAllMocks()
  hlsMock.instances.length = 0
  hlsMock.constructorOptions.length = 0
  localStorage.clear()
  mocks.fetchPlaybackConfig.mockResolvedValue(buildPlaybackConfig())
  resetPlaybackConfigCache()
  mocks.createTranscodeSession.mockResolvedValue({ sessionId: 's1', playlistUrl: '/hls/p.m3u8' })
  mocks.createTranscodeSessionByFileNode.mockResolvedValue({ sessionId: 's1', playlistUrl: '/hls/p.m3u8' })
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

  it('Hls 构造配置固定 startPosition: 0（转码流时间轴恒从 0 起），maxBufferLength 仍为 120', async () => {
    const { session } = createSession()

    await session.setupTranscode(0)

    expect(hlsMock.constructorOptions[0]).toEqual(
      expect.objectContaining({ startPosition: 0, maxBufferLength: 120 }),
    )
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

describe('useTranscodeSession 纯播放建会话分流', () => {
  it('pure=true 走 by-file-node API：不带 versionId，档位参数透传一致；外挂位图烧录参数正常透传（工单 04）', async () => {
    localStorage.setItem('jcloud.player.bitrateTier', '2000-720')
    await loadPlaybackConfig()
    const { session, itemId } = createSession(
      { effectiveBitRate: '5000000' },
      // 纯播放选中外挂位图字幕：烧录参数（字幕文件节点 ID）原样下发到 by-file-node 端点
      { pure: true, getBurnInParams: () => ({ externalSubtitleId: 'ext-1' }) },
    )
    itemId.value = 'fn-1'

    await session.setupTranscode(30_000)

    expect(mocks.createTranscodeSession).not.toHaveBeenCalled()
    // 恰好 3 个入参（无第 4 参 versionId），档位参数与已收录端点语义一致
    expect(mocks.createTranscodeSessionByFileNode).toHaveBeenCalledTimes(1)
    expect(mocks.createTranscodeSessionByFileNode.mock.calls[0]).toHaveLength(3)
    expect(mocks.createTranscodeSessionByFileNode).toHaveBeenCalledWith('fn-1', 30_000,
      expect.objectContaining({ targetBitrateKbps: 2000, maxHeight: 720 }))
    const optionsArg = mocks.createTranscodeSessionByFileNode.mock.calls[0][2] as Record<string, unknown>
    expect(optionsArg.externalSubtitleId).toBe('ext-1')
    // 会话生命周期不受影响：心跳照常、转码激活、基线为起点
    expect(mocks.transcodeHeartbeat).not.toHaveBeenCalled()
    expect(session.transcodeActive.value).toBe(true)
    expect(session.transcodeBaseMs.value).toBe(30_000)
    session.dispose()
  })

  it('pure=false 维持已收录路径：createTranscodeSession 带 versionId', async () => {
    const { session, currentVersionId } = createSession()
    currentVersionId.value = 'ver-2'

    await session.setupTranscode(0)

    expect(mocks.createTranscodeSessionByFileNode).not.toHaveBeenCalled()
    expect(mocks.createTranscodeSession).toHaveBeenCalledWith('item-1', 0, expect.anything(), 'ver-2')
    session.dispose()
  })
})
