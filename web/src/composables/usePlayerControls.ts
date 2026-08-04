/**
 * 播放器控制栏状态与交互 composable
 * - 播放/暂停、进度、音量、倍速、画中画、全屏等 video 元素状态同步与操作
 * - 控制栏自动隐藏（播放中 3 秒无操作隐藏，暂停或面板/弹层钉住时常显）
 * - 键盘快捷键：空格播放暂停、←/→ 快进快退 10s、↑/↓ 音量 ±10%、F 全屏
 * - 可选 timeline 提供绝对时间轴：转码流时间轴从 0 开始且 video.duration 随分片增长，
 *   显示用当前时间/时长/缓冲均换算为含偏移的绝对值；直放时透传 video 原始值
 */
import { computed, onBeforeUnmount, onMounted, ref, watch, type Ref } from 'vue'

/** 倍速档位（倍速弹层与控制栏倍速按钮共用） */
export const SPEED_OPTIONS = [0.5, 0.75, 1, 1.25, 1.5, 2]

const HIDE_DELAY_MS = 3000

export type PlayerControls = ReturnType<typeof usePlayerControls>

/** 绝对时间轴信息（转码时由 useMediaPlayback 经 MediaPlayPage 传入） */
export interface PlayerTimeline {
  /** 当前是否转码播放 */
  transcodeActive: boolean
  /** 转码会话起始偏移（毫秒）：流内时间 + base = 绝对时间 */
  baseMs: number
  /** 真实片长（毫秒），未知为 null */
  durationMs: number | null
}

/**
 * @param videoRef 播放器页面的 video 元素（始终渲染，不随播放源重建）
 * @param pinned 外部钉住状态（如选集面板打开），为 true 时控制栏不自动隐藏
 * @param timeline 可选绝对时间轴（转码修正显示用），直放可不传
 */
export function usePlayerControls(
  videoRef: Ref<HTMLVideoElement | null>,
  pinned: Ref<boolean>,
  timeline?: Ref<PlayerTimeline>,
) {
  const playing = ref(false)
  /** 流内当前时间（转码时为会话相对值，显示用值见返回的 currentTime） */
  const streamTime = ref(0)
  /** video.duration（转码时随增长式 playlist 变长，显示用值见返回的 duration） */
  const streamDuration = ref(0)
  /** 流内缓冲末端 */
  const streamBufferedEnd = ref(0)
  const volume = ref(1)
  const muted = ref(false)
  const playbackRate = ref(1)
  const isFullscreen = ref(false)
  /** 各弹层打开状态（经 v-model 穿透到对应 DropdownMenu，任一打开即钉住控制栏） */
  const rateMenuOpen = ref(false)
  const subtitleMenuOpen = ref(false)
  const bitrateMenuOpen = ref(false)
  const audioMenuOpen = ref(false)
  const versionMenuOpen = ref(false)
  const anyMenuOpen = computed(
    () => rateMenuOpen.value || subtitleMenuOpen.value || bitrateMenuOpen.value || audioMenuOpen.value
      || versionMenuOpen.value,
  )
  const controlsVisible = ref(true)
  const pipSupported = typeof document !== 'undefined'
    && 'pictureInPictureEnabled' in document
    && document.pictureInPictureEnabled

  // ---------- 绝对时间轴换算 ----------

  /** 转码时的流内→绝对偏移（秒），直放为 0 */
  const baseSec = computed(() => (timeline?.value.transcodeActive ? timeline.value.baseMs / 1000 : 0))
  /** 显示用当前时间（绝对秒） */
  const currentTime = computed(() => baseSec.value + streamTime.value)
  /** 显示用时长：转码且有真实片长时取 playbackInfo.durationMs，避免 video.duration 随分片变长 */
  const duration = computed(() => {
    const t = timeline?.value
    if (t?.transcodeActive && t.durationMs !== null && t.durationMs > 0) return t.durationMs / 1000
    return streamDuration.value
  })
  /** 显示用缓冲末端（绝对秒） */
  const bufferedEnd = computed(() => baseSec.value + streamBufferedEnd.value)

  let hideTimer: ReturnType<typeof setTimeout> | null = null

  // ---------- 控制栏自动隐藏 ----------

  function clearHideTimer() {
    if (hideTimer) {
      clearTimeout(hideTimer)
      hideTimer = null
    }
  }

  /** 唤醒控制栏并在条件允许时重新倒计时隐藏 */
  function wake() {
    controlsVisible.value = true
    clearHideTimer()
    if (playing.value && !pinned.value && !anyMenuOpen.value) {
      hideTimer = setTimeout(() => {
        controlsVisible.value = false
      }, HIDE_DELAY_MS)
    }
  }

  /** 点击画面区域切换控制栏显隐 */
  function toggleControls() {
    if (controlsVisible.value) {
      clearHideTimer()
      controlsVisible.value = false
    } else {
      wake()
    }
  }

  watch([playing, pinned, anyMenuOpen], wake)

  // ---------- 播放操作 ----------

  function togglePlay() {
    const video = videoRef.value
    if (!video) return
    if (video.paused) {
      video.play().catch(() => {})
    } else {
      video.pause()
    }
  }

  /** 流内相对跳转（键盘快捷键用；转码时超缓冲由 useMediaPlayback.handleSeeking 兜底重建） */
  function seekTo(seconds: number) {
    const video = videoRef.value
    if (!video) return
    const max = Number.isFinite(video.duration) ? video.duration : Number.MAX_SAFE_INTEGER
    video.currentTime = Math.min(Math.max(seconds, 0), max)
    streamTime.value = video.currentTime
  }

  function seekBy(delta: number) {
    const video = videoRef.value
    if (!video) return
    seekTo(video.currentTime + delta)
  }

  function setVolume(value: number) {
    const video = videoRef.value
    if (!video) return
    const clamped = Math.min(Math.max(value, 0), 1)
    video.volume = clamped
    if (clamped > 0) video.muted = false
  }

  function toggleMute() {
    const video = videoRef.value
    if (!video) return
    video.muted = !video.muted
  }

  function setRate(rate: number) {
    playbackRate.value = rate
    const video = videoRef.value
    if (!video) return
    // defaultPlaybackRate 保证切源触发 load() 后倍速仍保持
    video.defaultPlaybackRate = rate
    video.playbackRate = rate
  }

  function toggleFullscreen() {
    if (document.fullscreenElement) {
      Promise.resolve(document.exitFullscreen()).catch(() => {})
    } else {
      Promise.resolve(document.documentElement.requestFullscreen()).catch(() => {})
    }
  }

  async function togglePip() {
    const video = videoRef.value
    if (!video) return
    try {
      if (document.pictureInPictureElement) {
        await document.exitPictureInPicture()
      } else {
        await video.requestPictureInPicture()
      }
    } catch {
      // 浏览器限制（如无用户手势）时静默失败
    }
  }

  // ---------- video 事件同步 ----------

  function syncBuffered() {
    const video = videoRef.value
    if (!video) return
    streamBufferedEnd.value = video.buffered.length > 0
      ? video.buffered.end(video.buffered.length - 1)
      : 0
  }

  /** 转码时显示时长以 playbackInfo.durationMs 为准，忽略 video.duration */
  function syncDuration() {
    if (timeline?.value.transcodeActive) return
    const video = videoRef.value
    if (video) streamDuration.value = Number.isFinite(video.duration) ? video.duration : 0
  }

  function onLoadedMetadata() {
    syncDuration()
    const video = videoRef.value
    if (!video) return
    // 切源/切码率重建流后恢复所选倍速
    video.playbackRate = playbackRate.value
  }

  const videoListeners: Array<[string, () => void]> = [
    ['play', () => { playing.value = true }],
    ['pause', () => { playing.value = false }],
    ['timeupdate', () => {
      const video = videoRef.value
      if (video) streamTime.value = video.currentTime
    }],
    ['durationchange', syncDuration],
    ['loadedmetadata', onLoadedMetadata],
    ['progress', syncBuffered],
    ['volumechange', () => {
      const video = videoRef.value
      if (!video) return
      volume.value = video.volume
      muted.value = video.muted
    }],
    ['ratechange', () => {
      const video = videoRef.value
      if (video) playbackRate.value = video.playbackRate
    }],
  ]

  // ---------- 键盘快捷键 ----------

  function onKeydown(event: KeyboardEvent) {
    const target = event.target as HTMLElement | null
    if (target && (target.tagName === 'INPUT' || target.tagName === 'TEXTAREA' || target.isContentEditable)) {
      return
    }
    switch (event.key) {
      case ' ':
        event.preventDefault()
        togglePlay()
        wake()
        break
      case 'ArrowLeft':
        event.preventDefault()
        seekBy(-10)
        wake()
        break
      case 'ArrowRight':
        event.preventDefault()
        seekBy(10)
        wake()
        break
      case 'ArrowUp':
        event.preventDefault()
        setVolume(volume.value + 0.1)
        wake()
        break
      case 'ArrowDown':
        event.preventDefault()
        setVolume(volume.value - 0.1)
        wake()
        break
      case 'f':
      case 'F':
        event.preventDefault()
        toggleFullscreen()
        break
      default:
        break
    }
  }

  function onFullscreenChange() {
    isFullscreen.value = document.fullscreenElement !== null
  }

  // ---------- 生命周期 ----------

  onMounted(() => {
    const video = videoRef.value
    if (video) {
      volume.value = video.volume
      muted.value = video.muted
      for (const [event, handler] of videoListeners) {
        video.addEventListener(event, handler)
      }
    }
    document.addEventListener('fullscreenchange', onFullscreenChange)
    window.addEventListener('keydown', onKeydown)
  })

  onBeforeUnmount(() => {
    clearHideTimer()
    const video = videoRef.value
    if (video) {
      for (const [event, handler] of videoListeners) {
        video.removeEventListener(event, handler)
      }
    }
    document.removeEventListener('fullscreenchange', onFullscreenChange)
    window.removeEventListener('keydown', onKeydown)
  })

  return {
    playing,
    currentTime,
    duration,
    bufferedEnd,
    volume,
    muted,
    playbackRate,
    isFullscreen,
    pipSupported,
    rateMenuOpen,
    subtitleMenuOpen,
    bitrateMenuOpen,
    audioMenuOpen,
    versionMenuOpen,
    anyMenuOpen,
    controlsVisible,
    wake,
    toggleControls,
    togglePlay,
    seekTo,
    seekBy,
    setVolume,
    toggleMute,
    setRate,
    toggleFullscreen,
    togglePip,
  }
}
