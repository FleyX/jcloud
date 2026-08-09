/**
 * 媒体播放公共逻辑 composable
 * - 播放信息加载、直放/HLS 转码初始化、seek 超出缓冲重建转码会话、播放进度上报
 * - 音轨/字幕/码率档位状态与切换（直放切音轨自动转封装转码，限码率强制转码）
 */
import { computed, ref, type Ref } from 'vue'
import Hls from 'hls.js'
import type { MediaPlaybackInfoVo, MediaSubtitleItem } from '@/types/media'
import {
  closeTranscodeSession,
  createTranscodeSession,
  externalSubtitleUrl,
  fetchPlaybackInfo,
  subtitleUrl,
  transcodeCloseBeaconUrl,
  transcodeHeartbeat,
  updateMediaProgress,
  withToken,
} from '@/api/media'
import { getPlaybackConfig, loadPlaybackConfig } from './usePlaybackConfig'

const BITRATE_TIER_STORAGE_KEY = 'jcloud.player.bitrateTier'
const SUBTITLE_LANG_STORAGE_KEY = 'jcloud.player.subtitlePref'

/**
 * 当前展示的字幕（「无」为 null）
 */
export interface ActiveSubtitle {
  key: string
  label: string
  src: string
}

/**
 * 字幕选择项唯一标识：embedded:{index} / external:{subtitleId}
 */
export function subtitleItemKey(item: MediaSubtitleItem): string {
  return item.type === 'embedded' ? `embedded:${item.index}` : `external:${item.subtitleId}`
}

/** hevc/vp9/av1 转封装（-c:v copy）时的 MSE 探测 MIME（浏览器侧知识，保留前端） */
const REMUX_PROBE_MIME: Record<string, string> = {
  hevc: 'video/mp4; codecs="hvc1.1.6.L120.90"',
  vp9: 'video/mp4; codecs="vp09.00.10.08"',
  av1: 'video/mp4; codecs="av01.0.04M.08"',
}

/**
 * 转封装会话的视频是 -c:v copy，hevc/vp9/av1 需检测 MSE 是否支持，不支持则强制视频转码。
 * h264 不用检；Safari 走原生 HLS（无 MSE）不检测。
 * 需探测的 codec 集合 = 接口下发的转封装白名单减 h264（ADR 0024），不再前端硬编码。
 */
function needsForceVideoTranscode(videoCodec: string | null): boolean {
  if (!videoCodec || !Hls.isSupported()) return false
  const probeCodecs = (getPlaybackConfig()?.remux.videoCopyCodecs ?? []).filter((codec) => codec !== 'h264')
  if (!probeCodecs.includes(videoCodec.toLowerCase())) return false
  const mime = REMUX_PROBE_MIME[videoCodec.toLowerCase()]
  return mime !== undefined && !MediaSource.isTypeSupported(mime)
}

export function useMediaPlayback(videoRef: Ref<HTMLVideoElement | null>) {
  const loading = ref(true)
  const errorMsg = ref('')
  const playbackInfo = ref<MediaPlaybackInfoVo | null>(null)
  /** 已加载条目的 id（上报进度必须使用它而非路由参数） */
  const itemId = ref<string | null>(null)
  const audioIndex = ref<number | null>(null)
  const subtitleKey = ref<string | null>(null)
  // 播放源代际：每次重建播放源（直放/转码）自增，用于强制重建 <track> 元素，
  // 规避复用的 track 元素在 video.src 变更后卡住 0 cues 的浏览器状态问题
  const sourceEpoch = ref(0)
  const bitrateTierKey = ref(localStorage.getItem(BITRATE_TIER_STORAGE_KEY) || 'original')
  /** 当前播放版本（电影为文件明细行 ID，取播放信息响应 versionId；剧集/其他为 null） */
  const currentVersionId = ref<string | null>(null)
  /** 当前是否处于转码播放（直放切音轨/限码率后转入转码并停留） */
  const transcodeActive = ref(false)

  let hls: Hls | null = null
  let progressTimer: ReturnType<typeof setInterval> | null = null
  let destroyed = false
  /** 转码会话的起始偏移：转码流时间轴从 0 开始，绝对进度 = base + currentTime */
  const transcodeBaseMs = ref(0)
  /** 当前转码会话 id 与心跳定时器：播放页打开期间每 5s 心跳一次，后端 90s 无心跳回收会话 */
  let transcodeSessionId: string | null = null
  let heartbeatTimer: ReturnType<typeof setInterval> | null = null

  /** 页面卸载（关标签页/刷新）时用 sendBeacon 主动关闭会话，即时回收 ffmpeg 与缓存 */
  function handlePageHide() {
    if (transcodeSessionId) {
      navigator.sendBeacon(transcodeCloseBeaconUrl(transcodeSessionId))
      transcodeSessionId = null
    }
  }
  window.addEventListener('pagehide', handlePageHide)

  function startHeartbeat(sessionId: string) {
    stopHeartbeat()
    transcodeSessionId = sessionId
    heartbeatTimer = setInterval(() => transcodeHeartbeat(sessionId), 5_000)
  }

  function stopHeartbeat() {
    if (heartbeatTimer) {
      clearInterval(heartbeatTimer)
      heartbeatTimer = null
    }
  }

  /** 停止心跳并主动关闭当前转码会话（重建会话/切回直放/退出播放时调用） */
  function stopTranscodeSession() {
    stopHeartbeat()
    if (transcodeSessionId) {
      closeTranscodeSession(transcodeSessionId)
      transcodeSessionId = null
    }
  }

  const subtitles = computed(() => playbackInfo.value?.subtitles ?? [])
  const showAudioGroup = computed(() => (playbackInfo.value?.audioTracks.length ?? 0) > 1)
  const showSubtitleGroup = computed(() => subtitles.value.length >= 1)

  const activeSubtitle = computed<ActiveSubtitle | null>(() => {
    const key = subtitleKey.value
    const id = itemId.value
    if (!key || !id) return null
    const item = subtitles.value.find((s) => subtitleItemKey(s) === key)
    if (!item) return null
    const versionId = currentVersionId.value ?? undefined
    // 转码播放的字幕时间轴相对当前转码会话起点偏移，直放使用原片时间轴（offset 0）
    const offsetMs = transcodeActive.value ? transcodeBaseMs.value : 0
    const src = item.type === 'embedded' && item.index !== null
      ? subtitleUrl(id, item.index, versionId, offsetMs)
      : item.subtitleId
        ? externalSubtitleUrl(id, item.subtitleId, versionId, offsetMs)
        : null
    return src ? { key, label: item.label, src } : null
  })

  // ---------- 码率档位 ----------

  /** 非原画档且档位码率低于实际码率时返回强制转码参数，否则按原画处理 */
  function resolveBitrateParams(info: MediaPlaybackInfoVo): { targetBitrateKbps: number; maxHeight: number } | null {
    const tiers = getPlaybackConfig()?.bitrateTiers ?? []
    const tier = tiers.find((t) => t.key === bitrateTierKey.value) ?? tiers[0]
    if (!tier || tier.kbps === null || tier.maxHeight === null) return null
    const effective = info.effectiveBitRate !== null ? Number(info.effectiveBitRate) : NaN
    if (!Number.isFinite(effective) || tier.kbps * 1000 >= effective) return null
    return { targetBitrateKbps: tier.kbps, maxHeight: tier.maxHeight }
  }

  function buildTranscodeOptions(info: MediaPlaybackInfoVo) {
    const bitrate = resolveBitrateParams(info)
    return {
      audioIndex: audioIndex.value ?? undefined,
      targetBitrateKbps: bitrate?.targetBitrateKbps,
      maxHeight: bitrate?.maxHeight,
      // 传了 targetBitrateKbps 必然视频转码，只有转封装会话才需要 MSE 检测
      forceVideoTranscode: bitrate ? undefined : needsForceVideoTranscode(info.videoCodec) || undefined,
    }
  }

  // ---------- 播放初始化 ----------

  function setupDirect(url: string, startMs: number) {
    destroyHls()
    const video = videoRef.value
    if (!video) return
    transcodeActive.value = false
    transcodeBaseMs.value = 0
    video.src = withToken(url)
    video.currentTime = startMs / 1000
    sourceEpoch.value += 1
    video.play().catch(() => {})
  }

  async function setupTranscode(startMs: number) {
    const info = playbackInfo.value
    const id = itemId.value
    if (!info || !id) return
    destroyHls()
    const session = await createTranscodeSession(id, Math.floor(startMs), buildTranscodeOptions(info),
      currentVersionId.value ?? undefined)
    const video = videoRef.value
    if (!video || destroyed) return
    startHeartbeat(session.sessionId)
    transcodeActive.value = true
    transcodeBaseMs.value = startMs
    const url = withToken(session.playlistUrl)
    if (Hls.isSupported()) {
      // 转码播放缓冲调大到 120s，吸收转码速度波动，避免播放追上分片产出导致卡顿
      hls = new Hls({ maxBufferLength: 120 })
      hls.loadSource(url)
      hls.attachMedia(video)
      sourceEpoch.value += 1
      hls.on(Hls.Events.MANIFEST_PARSED, () => {
        video.currentTime = 0
        video.play().catch(() => {})
      })
      hls.on(Hls.Events.ERROR, (_event, data) => {
        if (data.fatal) {
          errorMsg.value = data.error?.message ?? '转码播放失败'
        }
      })
    } else if (video.canPlayType('application/vnd.apple.mpegurl')) {
      // Safari 原生 HLS
      video.src = url
      sourceEpoch.value += 1
      video.play().catch(() => {})
    } else {
      throw new Error('当前浏览器不支持 HLS 播放')
    }
  }


  /** 当前绝对播放进度（毫秒） */
  function currentAbsoluteMs(): number {
    const video = videoRef.value
    if (!video) return 0
    return transcodeBaseMs.value + video.currentTime * 1000
  }

  /**
   * 按当前 码率档位/音轨 选择与播放信息协调播放方式：
   * 需要转码则带当前进度重建会话，可回直放则回直放，直放且无需变更时保持现状。
   */
  async function reconcilePlayback() {
    const info = playbackInfo.value
    if (!info) return
    const currentMs = currentAbsoluteMs()
    // 直放模式下选了非默认音轨时，必须转码（后端视频转封装）才能生效
    const forceTranscode = resolveBitrateParams(info) !== null
      || (info.mode === 'direct' && audioIndex.value !== null)
    if (info.mode === 'transcode' || forceTranscode) {
      await setupTranscode(currentMs)
    } else if (transcodeActive.value) {
      setupDirect(info.directUrl!, currentMs)
    }
  }

  // ---------- 对外操作 ----------

  /**
   * 加载播放信息并初始化播放。startMs 为空时按历史进度续播。
   * versionId 为电影版本明细行 ID（可选）：指定时播放该版本，缺省时后端按续播语义定位。
   */
  async function start(id: string, startMs?: number, versionId?: string) {
    destroyed = false
    teardown()
    loading.value = true
    errorMsg.value = ''
    try {
      // 并行拉取播放信息与全局播放配置（配置单例缓存，进入播放流程时首次加载）
      const [config, info] = await Promise.all([loadPlaybackConfig(), fetchPlaybackInfo(id, versionId)])
      if (destroyed) return
      playbackInfo.value = info
      itemId.value = id
      // 以响应解析的实际版本为准：缺省请求时后端按续播语义定位，剧集/其他可能为 null
      currentVersionId.value = playbackInfo.value?.versionId ?? null
      applyDefaultSubtitle(playbackInfo.value.subtitles ?? [])
      const durationMs = Number(playbackInfo.value.durationMs ?? 0)
      let resumeMs = startMs ?? Number(playbackInfo.value.progressMs ?? 0)
      // 进度已达看完阈值视为看完，从头播放；也防止按超出时长的进度启动转码导致空切片
      if (durationMs > 0 && resumeMs >= durationMs * config.finishedRatio) {
        resumeMs = 0
      }
      if (resolveBitrateParams(playbackInfo.value) !== null || playbackInfo.value.mode === 'transcode') {
        await setupTranscode(resumeMs)
      } else {
        setupDirect(playbackInfo.value.directUrl!, resumeMs)
      }
      if (destroyed) return
      startProgressTimer()
    } catch (e) {
      if (!destroyed) {
        errorMsg.value = e instanceof Error ? e.message : '播放初始化失败'
      }
    } finally {
      if (!destroyed) loading.value = false
    }
  }

  /** 停止播放并释放资源（组件卸载/关闭时调用） */
  function stop() {
    destroyed = true
    window.removeEventListener('pagehide', handlePageHide)
    teardown()
    loading.value = true
    errorMsg.value = ''
    currentVersionId.value = null
  }

  function selectAudioTrack(index: number | null) {
    if (index === audioIndex.value || !playbackInfo.value) return
    audioIndex.value = index
    reconcilePlayback().catch((e) => {
      errorMsg.value = e instanceof Error ? e.message : '音轨切换失败'
    })
  }

  function selectSubtitle(key: string | null) {
    subtitleKey.value = key
    if (key) {
      const item = subtitles.value.find((s) => subtitleItemKey(s) === key)
      // 记忆语言偏好，新片按 language 或 label 匹配
      if (item) localStorage.setItem(SUBTITLE_LANG_STORAGE_KEY, item.language || item.label)
    }
  }

  function selectBitrateTier(key: string) {
    if (key === bitrateTierKey.value || !playbackInfo.value) return
    bitrateTierKey.value = key
    localStorage.setItem(BITRATE_TIER_STORAGE_KEY, key)
    reconcilePlayback().catch((e) => {
      errorMsg.value = e instanceof Error ? e.message : '码率切换失败'
    })
  }

  /**
   * 切换播放版本（仅电影多版本有效）：以当前观看位置为续播点，按新版本重拉播放信息并换流。
   * 进度共享在电影行；start 初始化时经 teardown 顺带上报当前版本进度，续播语义不变。
   */
  async function selectVersion(versionId: string) {
    const id = itemId.value
    if (!id || versionId === currentVersionId.value) return
    await start(id, currentAbsoluteMs(), versionId)
  }

  /**
   * 转码模式下 seek 超出已缓冲范围时，从目标位置重建转码会话
   */
  async function handleSeeking() {
    const video = videoRef.value
    if (!video || !transcodeActive.value || !hls) return
    const target = video.currentTime
    let bufferedEnd = 0
    if (video.buffered.length > 0) {
      bufferedEnd = video.buffered.end(video.buffered.length - 1)
    }
    if (target > bufferedEnd + 5) {
      destroyHls()
      await setupTranscode(transcodeBaseMs.value + target * 1000)
    }
  }

  /**
   * 按绝对秒数跳转。转码流时间轴从 0 开始，入参为含 transcodeBaseMs 偏移的绝对时间：
   * 目标在已缓冲范围内直接设置 currentTime（走原生 seeking，handleSeeking 兜底），
   * 否则销毁当前 HLS 会话并从目标位置重建转码会话；直放模式直接设置 currentTime。
   */
  async function seekToAbsolute(absoluteSec: number) {
    const video = videoRef.value
    if (!video) return
    if (!transcodeActive.value) {
      const max = Number.isFinite(video.duration) ? video.duration : Number.MAX_SAFE_INTEGER
      video.currentTime = Math.min(Math.max(absoluteSec, 0), max)
      return
    }
    const durationMs = Number(playbackInfo.value?.durationMs ?? 0)
    const target = durationMs > 0
      ? Math.min(Math.max(absoluteSec, 0), durationMs / 1000)
      : Math.max(absoluteSec, 0)
    // 转码流时间轴从 0 开始（对应绝对 baseSec），currentTime/buffered 均为流内相对秒，
    // 比较与赋值前必须把绝对目标换算为流内时间；目标早于当前会话起点则只能重建
    const streamTarget = target - transcodeBaseMs.value / 1000
    let bufferedEnd = 0
    if (video.buffered.length > 0) {
      bufferedEnd = video.buffered.end(video.buffered.length - 1)
    }
    if (streamTarget >= 0 && streamTarget <= bufferedEnd) {
      video.currentTime = streamTarget
    } else {
      destroyHls()
      await setupTranscode(target * 1000)
    }
  }

  function reportProgress() {
    const video = videoRef.value
    const id = itemId.value
    if (!video || !id) return
    const durationMs = Number(playbackInfo.value?.durationMs ?? 0)
    let progressMs = Math.floor(currentAbsoluteMs())
    // 转码会话停滞时流内时间可能继续增长，进度钳制在片长内
    if (durationMs > 0) progressMs = Math.min(progressMs, durationMs)
    if (progressMs <= 0) return
    updateMediaProgress(id, progressMs, currentVersionId.value ?? undefined).catch(() => {})
  }

  /** track 元素加载后强制 showing（「无」时元素被移除即全部禁用） */
  function handleTrackLoad(event: Event) {
    const track = (event.target as HTMLTrackElement).track
    if (track) track.mode = 'showing'
  }

  // ---------- 内部 ----------

  /** 默认字幕：defaulted 标记 > localStorage 语言偏好 > 无 */
  function applyDefaultSubtitle(list: MediaSubtitleItem[]) {
    const defaulted = list.find((s) => s.defaulted)
    if (defaulted) {
      subtitleKey.value = subtitleItemKey(defaulted)
      return
    }
    const preferred = localStorage.getItem(SUBTITLE_LANG_STORAGE_KEY)
    if (preferred) {
      const match = list.find((s) => s.language === preferred || s.label === preferred)
      if (match) {
        subtitleKey.value = subtitleItemKey(match)
        return
      }
    }
    subtitleKey.value = null
  }

  function startProgressTimer() {
    stopProgressTimer()
    progressTimer = setInterval(() => {
      reportProgress()
    }, 10_000)
  }

  function stopProgressTimer() {
    if (progressTimer) {
      clearInterval(progressTimer)
      progressTimer = null
    }
  }

  function destroyHls() {
    if (hls) {
      hls.destroy()
      hls = null
    }
    // 销毁 HLS 客户端即放弃当前转码会话（重建/切直放/退出均走这里）
    stopTranscodeSession()
  }

  function teardown() {
    stopProgressTimer()
    reportProgress()
    destroyHls()
    if (videoRef.value) {
      videoRef.value.pause()
      videoRef.value.removeAttribute('src')
      videoRef.value.load()
    }
    playbackInfo.value = null
    itemId.value = null
    audioIndex.value = null
    subtitleKey.value = null
    transcodeActive.value = false
    transcodeBaseMs.value = 0
  }

  return {
    loading,
    errorMsg,
    playbackInfo,
    currentVersionId,
    audioIndex,
    sourceEpoch,
    subtitleKey,
    bitrateTierKey,
    activeSubtitle,
    showAudioGroup,
    showSubtitleGroup,
    transcodeActive,
    transcodeBaseMs,
    start,
    stop,
    handleSeeking,
    seekToAbsolute,
    reportProgress,
    handleTrackLoad,
    selectAudioTrack,
    selectSubtitle,
    selectBitrateTier,
    selectVersion,
  }
}
