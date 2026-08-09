/**
 * 转码会话管理 composable
 * - HLS 实例生命周期（创建/销毁）、转码会话 id 与心跳（5s）、pagehide sendBeacon 主动关闭会话
 * - 码率档位状态（localStorage 记忆）与转码参数构造（消费票据 11 的 usePlaybackConfig）
 * - seek 超出已缓冲范围时从目标位置重建转码会话
 * - 供 useMediaPlayback 编排：主 composable 只保留直放/HLS 选择与进度上报
 */
import { ref, type Ref } from 'vue'
import Hls from 'hls.js'
import type { MediaPlaybackInfoVo } from '@/types/media'
import {
  closeTranscodeSession,
  createTranscodeSession,
  transcodeCloseBeaconUrl,
  transcodeHeartbeat,
  withToken,
} from '@/api/media'
import { getPlaybackConfig } from './usePlaybackConfig'

const BITRATE_TIER_STORAGE_KEY = 'jcloud.player.bitrateTier'

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

export interface TranscodeSessionDeps {
  videoRef: Ref<HTMLVideoElement | null>
  playbackInfo: Ref<MediaPlaybackInfoVo | null>
  /** 已加载条目的 id（建会话与上报进度必须使用它而非路由参数） */
  itemId: Ref<string | null>
  /** 当前播放版本（电影为文件明细行 ID，取播放信息响应 versionId；剧集/其他为 null） */
  currentVersionId: Ref<string | null>
  audioIndex: Ref<number | null>
  /** 转码播放失败等错误信息写入（HLS fatal 错误） */
  errorMsg: Ref<string>
  /** 播放源代际：每次重建播放源（直放/转码）自增，用于强制重建 <track> 元素 */
  sourceEpoch: Ref<number>
  /** 播放生命周期标记：stop 后置真，异步建会话返回后不再挂载 */
  destroyed: Ref<boolean>
}

export function useTranscodeSession(deps: TranscodeSessionDeps) {
  const { videoRef, playbackInfo, itemId, currentVersionId, audioIndex, errorMsg, sourceEpoch, destroyed } = deps

  /** 码率档位 key（localStorage 记忆，默认原画） */
  const bitrateTierKey = ref(localStorage.getItem(BITRATE_TIER_STORAGE_KEY) || 'original')
  /** 当前是否处于转码播放（直放切音轨/限码率后转入转码并停留） */
  const transcodeActive = ref(false)
  /** 转码会话的起始偏移：转码流时间轴从 0 开始，绝对进度 = base + currentTime */
  const transcodeBaseMs = ref(0)

  let hls: Hls | null = null
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

  // ---------- 会话生命周期 ----------

  async function setupTranscode(startMs: number) {
    const info = playbackInfo.value
    const id = itemId.value
    if (!info || !id) return
    destroyHls()
    const session = await createTranscodeSession(id, Math.floor(startMs), buildTranscodeOptions(info),
      currentVersionId.value ?? undefined)
    const video = videoRef.value
    if (!video || destroyed.value) return
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

  /** 销毁 HLS 客户端并关闭当前转码会话（重建/切直放/退出均走这里） */
  function destroyHls() {
    if (hls) {
      hls.destroy()
      hls = null
    }
    // 销毁 HLS 客户端即放弃当前转码会话
    stopTranscodeSession()
  }

  // ---------- seek 与缓冲重建 ----------

  /** 转码模式下 seek 超出已缓冲范围时，从目标位置重建转码会话 */
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
   * 按绝对秒数跳转（转码分支）。转码流时间轴从 0 开始，入参为含 transcodeBaseMs 偏移的绝对时间：
   * 目标在已缓冲范围内直接设置 currentTime（走原生 seeking，handleSeeking 兜底），
   * 否则销毁当前 HLS 会话并从目标位置重建转码会话；直放模式由主 composable 的 seekToAbsolute 处理。
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

  // ---------- 对外操作 ----------

  /**
   * 切换码率档位：仅更新状态并持久化记忆，返回是否发生切换；
   * 由主 composable 在返回 true 时按新档位协调播放方式（转码/直放）。
   */
  function selectBitrateTier(key: string): boolean {
    if (key === bitrateTierKey.value || !playbackInfo.value) return false
    bitrateTierKey.value = key
    localStorage.setItem(BITRATE_TIER_STORAGE_KEY, key)
    return true
  }

  /** 释放资源（组件卸载时由主 composable 调用）：注销 pagehide 监听并关闭转码会话 */
  function dispose() {
    window.removeEventListener('pagehide', handlePageHide)
    destroyHls()
  }

  return {
    bitrateTierKey,
    transcodeActive,
    transcodeBaseMs,
    setupTranscode,
    destroyHls,
    handleSeeking,
    seekToAbsolute,
    selectBitrateTier,
    resolveBitrateParams,
    dispose,
  }
}
