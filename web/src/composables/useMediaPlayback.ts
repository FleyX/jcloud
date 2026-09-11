/**
 * 媒体播放公共逻辑 composable（编排层）
 * - 播放信息加载、直放/HLS 选择、seek 超出缓冲重建转码会话、播放进度上报
 * - 转码会话生命周期（HLS/心跳/码率档位/seek 重建）由 useTranscodeSession 承载
 * - 字幕选择（默认字幕/语言偏好记忆/track 挂载）由 useSubtitleSelection 承载
 * - 音轨切换（直放切音轨自动转封装转码）与播放方式协调在此编排
 */
import { computed, ref, type Ref } from 'vue'
import type { MediaPlaybackInfoVo } from '@/types/media'
import { fetchPlaybackInfo, fetchPlaybackInfoByFileNode, updateMediaProgress } from '@/api/media'
import { loadPlaybackConfig } from './usePlaybackConfig'
import { useTranscodeSession } from './useTranscodeSession'
import { useSubtitleSelection } from './useSubtitleSelection'
import { canNativeDirectPlay } from '@/utils/mediaCapability'

// 工具函数/类型保持原导出路径可用（PlayerControlBar 等直接导入）
export { subtitleItemKey, type ActiveSubtitle } from './useSubtitleSelection'

export function useMediaPlayback(videoRef: Ref<HTMLVideoElement | null>) {
  const loading = ref(true)
  const errorMsg = ref('')
  const playbackInfo = ref<MediaPlaybackInfoVo | null>(null)
  /** 已加载条目的 id（上报进度必须使用它而非路由参数） */
  const itemId = ref<string | null>(null)
  const audioIndex = ref<number | null>(null)
  // 播放源代际：每次重建播放源（直放/转码）自增，用于强制重建 <track> 元素，
  // 规避复用的 track 元素在 video.src 变更后卡住 0 cues 的浏览器状态问题
  const sourceEpoch = ref(0)
  /** 当前播放版本（电影为文件明细行 ID，取播放信息响应 versionId；剧集/其他为 null） */
  const currentVersionId = ref<string | null>(null)
  /** 播放生命周期标记：stop 后置真，异步初始化/建会话不再落地 */
  const destroyed = ref(false)
  /**
   * 纯播放模式标记（未收录文件直放）：对媒体数据零写入——不拉详情/选集、
   * 不上报进度、不启动进度定时器；itemId 照常置为 fileNodeId 供后续链路使用
   */
  const pure = ref(false)

  const transcode = useTranscodeSession({
    videoRef,
    playbackInfo,
    itemId,
    currentVersionId,
    audioIndex,
    errorMsg,
    sourceEpoch,
    destroyed,
    // deps 为延迟求值：start 中赋值 pure 后建会话分流才生效（纯播放走 by-file-node 端点）
    pure,
    // 位图烧录参数延迟求值：subtitle 在本函数稍后创建，闭包运行时才读取，无循环依赖
    getBurnInParams: () => subtitle.burnInSubtitle.value,
  })

  const subtitles = computed(() => playbackInfo.value?.subtitles ?? [])
  const subtitle = useSubtitleSelection({
    subtitles,
    itemId,
    currentVersionId,
    transcodeActive: transcode.transcodeActive,
    transcodeBaseMs: transcode.transcodeBaseMs,
  })

  const showAudioGroup = computed(() => (playbackInfo.value?.audioTracks.length ?? 0) > 1)
  const showSubtitleGroup = computed(() => subtitles.value.length >= 1)

  let progressTimer: ReturnType<typeof setInterval> | null = null

  // ---------- 播放初始化 ----------

  function setupDirect(url: string, startMs: number) {
    transcode.destroyHls()
    const video = videoRef.value
    if (!video) return
    transcode.transcodeActive.value = false
    transcode.transcodeBaseMs.value = 0
    video.src = url
    video.currentTime = startMs / 1000
    sourceEpoch.value += 1
    video.play().catch(() => {})
  }

  /** 当前绝对播放进度（毫秒） */
  function currentAbsoluteMs(): number {
    const video = videoRef.value
    if (!video) return 0
    return transcode.transcodeBaseMs.value + video.currentTime * 1000
  }

  /**
   * 按当前 码率档位/音轨 选择与播放信息协调播放方式：
   * 需要转码则带当前进度重建会话，可回直放则回直放，直放且无需变更时保持现状。
   */
  async function reconcilePlayback() {
    const info = playbackInfo.value
    if (!info) return
    const currentMs = currentAbsoluteMs()
    // 直放模式下选了非默认音轨时，必须转码（后端视频转封装）才能生效；位图字幕必须烧录转码；
    // 直放但浏览器实际无法解码该 容器+编码（如 mov+hevc）时同样静默降级转码，避免黑屏只有声音
    const forceTranscode = transcode.resolveBitrateParams(info) !== null
      || (info.mode === 'direct' && audioIndex.value !== null)
      || subtitle.burnInSubtitle.value !== null
      || (info.mode === 'direct' && !canNativeDirectPlay(info.container, info.videoCodec))
    if (info.mode === 'transcode' || forceTranscode) {
      await transcode.setupTranscode(currentMs)
    } else if (transcode.transcodeActive.value) {
      setupDirect(info.directUrl!, currentMs)
    }
  }

  // ---------- 对外操作 ----------

  /**
   * 加载播放信息并初始化播放。startMs 为空时按历史进度续播。
   * versionId 为电影版本明细行 ID（可选）：指定时播放该版本，缺省时后端按续播语义定位。
   * options.pure 为纯播放模式（未收录文件）：拉取走 by-file-node 接口且全链路零写入。
   */
  async function start(id: string, startMs?: number, versionId?: string, options?: { pure?: boolean }) {
    destroyed.value = false
    teardown()
    pure.value = options?.pure ?? false
    loading.value = true
    errorMsg.value = ''
    try {
      // 并行拉取播放信息与全局播放配置（配置单例缓存，进入播放流程时首次加载）
      const fetchInfo = pure.value ? fetchPlaybackInfoByFileNode(id) : fetchPlaybackInfo(id, versionId)
      const [config, info] = await Promise.all([loadPlaybackConfig(), fetchInfo])
      if (destroyed.value) return
      playbackInfo.value = info
      itemId.value = id
      // 以响应解析的实际版本为准：缺省请求时后端按续播语义定位，剧集/其他可能为 null
      currentVersionId.value = playbackInfo.value?.versionId ?? null
      subtitle.applyDefaultSubtitle(playbackInfo.value.subtitles ?? [])
      const durationMs = Number(playbackInfo.value.durationMs ?? 0)
      let resumeMs = startMs ?? Number(playbackInfo.value.progressMs ?? 0)
      // 进度已达看完阈值视为看完，从头播放；也防止按超出时长的进度启动转码导致空切片
      if (durationMs > 0 && resumeMs >= durationMs * config.finishedRatio) {
        resumeMs = 0
      }
      if (transcode.resolveBitrateParams(playbackInfo.value) !== null
        || playbackInfo.value.mode === 'transcode'
        // 直放但浏览器实际无法解码该 容器+编码（如 mov+hevc）：静默降级走转码，档位/界面无变化
        || (playbackInfo.value.mode === 'direct'
          && !canNativeDirectPlay(playbackInfo.value.container, playbackInfo.value.videoCodec))) {
        await transcode.setupTranscode(resumeMs)
      } else {
        setupDirect(playbackInfo.value.directUrl!, resumeMs)
      }
      if (destroyed.value) return
      startProgressTimer()
    } catch (e) {
      if (!destroyed.value) {
        errorMsg.value = e instanceof Error ? e.message : '播放初始化失败'
      }
    } finally {
      if (!destroyed.value) loading.value = false
    }
  }

  /** 停止播放并释放资源（组件卸载/关闭时调用） */
  function stop() {
    destroyed.value = true
    transcode.dispose()
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

  /**
   * 切换字幕：仅当位图状态发生变化（进入/退出/换另一位图项）时重建播放源——
   * 文本轨/「无」之间切换由 <track> 元素承担，无需重建。
   * 烧录转码失败经 reconcile 的 catch 写入 errorMsg（播放页已有错误展示）。
   */
  function selectSubtitle(key: string | null) {
    const prevBurnIn = subtitle.burnInSubtitle.value
    subtitle.selectSubtitle(key)
    if (prevBurnIn !== subtitle.burnInSubtitle.value) {
      reconcilePlayback().catch((e) => {
        errorMsg.value = e instanceof Error ? e.message : '字幕切换失败'
      })
    }
  }

  function selectBitrateTier(key: string) {
    if (transcode.selectBitrateTier(key)) {
      reconcilePlayback().catch((e) => {
        errorMsg.value = e instanceof Error ? e.message : '码率切换失败'
      })
    }
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

  function reportProgress() {
    // 纯播放模式零写入：不上报进度（teardown/stop 经同一 guard 自然跳过）
    if (pure.value) return
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

  // ---------- 内部 ----------

  function startProgressTimer() {
    stopProgressTimer()
    // 纯播放模式零写入：不启动进度定时器
    if (pure.value) return
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

  function teardown() {
    stopProgressTimer()
    reportProgress()
    pure.value = false
    transcode.destroyHls()
    if (videoRef.value) {
      videoRef.value.pause()
      videoRef.value.removeAttribute('src')
      videoRef.value.load()
    }
    playbackInfo.value = null
    itemId.value = null
    audioIndex.value = null
    subtitle.subtitleKey.value = null
    transcode.transcodeActive.value = false
    transcode.transcodeBaseMs.value = 0
  }

  return {
    loading,
    errorMsg,
    playbackInfo,
    currentVersionId,
    audioIndex,
    sourceEpoch,
    subtitleKey: subtitle.subtitleKey,
    bitrateTierKey: transcode.bitrateTierKey,
    activeSubtitle: subtitle.activeSubtitle,
    showAudioGroup,
    showSubtitleGroup,
    transcodeActive: transcode.transcodeActive,
    transcodeBaseMs: transcode.transcodeBaseMs,
    start,
    stop,
    handleSeeking: transcode.handleSeeking,
    seekToAbsolute: transcode.seekToAbsolute,
    reportProgress,
    handleTrackLoad: subtitle.handleTrackLoad,
    selectAudioTrack,
    selectSubtitle,
    selectBitrateTier,
    selectVersion,
  }
}
