<script setup lang="ts">
/**
 * 媒体播放器弹窗
 * - 支持直放（原生 video + Range）与 HLS 转码播放（hls.js）
 * - 支持续播、进度上报、音轨选择、文本字幕
 */
import { onBeforeUnmount, ref, watch } from 'vue'
import Hls from 'hls.js'
import { X, LoaderCircle } from '@lucide/vue'
import type { MediaItemVo, MediaPlaybackInfoVo } from '@/types/media'
import {
  createTranscodeSession,
  fetchPlaybackInfo,
  subtitleUrl,
  updateMediaProgress,
  withToken,
} from '@/api/media'

interface Props {
  open: boolean
  item: MediaItemVo | null
}

const props = defineProps<Props>()
const emit = defineEmits<{
  close: []
}>()

const videoRef = ref<HTMLVideoElement | null>(null)
const loading = ref(true)
const errorMsg = ref('')
const playbackInfo = ref<MediaPlaybackInfoVo | null>(null)
const audioIndex = ref<number | null>(null)
const subtitleIndex = ref<number | null>(null)

let hls: Hls | null = null
let progressTimer: ReturnType<typeof setInterval> | null = null
let destroyed = false

watch(
  () => props.open,
  async (open) => {
    if (open && props.item) {
      destroyed = false
      await startPlayback()
    } else {
      teardown()
    }
  },
)

onBeforeUnmount(() => {
  destroyed = true
  teardown()
})

async function startPlayback(startMs?: number) {
  if (!props.item) return
  loading.value = true
  errorMsg.value = ''
  try {
    if (!playbackInfo.value) {
      playbackInfo.value = await fetchPlaybackInfo(props.item.id)
    }
    const info = playbackInfo.value
    const resumeMs = startMs ?? info.progressMs ?? 0
    if (info.mode === 'direct') {
      setupDirect(info.directUrl!, resumeMs)
    } else {
      await setupTranscode(resumeMs)
    }
    startProgressTimer()
  } catch (e) {
    errorMsg.value = e instanceof Error ? e.message : '播放初始化失败'
  } finally {
    loading.value = false
  }
}

function setupDirect(url: string, startMs: number) {
  destroyHls()
  const video = videoRef.value
  if (!video) return
  video.src = withToken(url)
  video.currentTime = startMs / 1000
  video.play().catch(() => {})
}

async function setupTranscode(startMs: number) {
  if (!props.item) return
  destroyHls()
  const session = await createTranscodeSession(props.item.id, startMs, audioIndex.value ?? undefined)
  const video = videoRef.value
  if (!video || destroyed) return
  const url = withToken(session.playlistUrl)
  if (Hls.isSupported()) {
    hls = new Hls({ maxBufferLength: 30 })
    hls.loadSource(url)
    hls.attachMedia(video)
    hls.on(Hls.Events.MANIFEST_PARSED, () => {
      video.currentTime = 0
      video.play().catch(() => {})
    })
  } else if (video.canPlayType('application/vnd.apple.mpegurl')) {
    // Safari 原生 HLS
    video.src = url
    video.play().catch(() => {})
  } else {
    throw new Error('当前浏览器不支持 HLS 播放')
  }
}

/**
 * 转码模式下 seek 超出已缓冲范围时，从目标位置重建转码会话
 */
async function handleSeeking() {
  const video = videoRef.value
  const info = playbackInfo.value
  if (!video || !info || info.mode !== 'transcode' || !hls) return
  const target = video.currentTime
  let bufferedEnd = 0
  if (video.buffered.length > 0) {
    bufferedEnd = video.buffered.end(video.buffered.length - 1)
  }
  if (target > bufferedEnd + 5) {
    destroyHls()
    await setupTranscode(target * 1000)
  }
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

function reportProgress() {
  const video = videoRef.value
  if (!props.item || !video || video.currentTime <= 0) return
  updateMediaProgress(props.item.id, Math.floor(video.currentTime * 1000)).catch(() => {})
}

function handleAudioTrackChange() {
  // 切换音轨需要重建转码会话
  const video = videoRef.value
  const currentMs = video ? video.currentTime * 1000 : 0
  if (playbackInfo.value?.mode === 'transcode') {
    setupTranscode(currentMs).catch(() => {})
  }
}

function destroyHls() {
  if (hls) {
    hls.destroy()
    hls = null
  }
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
  audioIndex.value = null
  subtitleIndex.value = null
  loading.value = true
  errorMsg.value = ''
}

function handleClose() {
  teardown()
  emit('close')
}
</script>

<template>
  <div
    v-if="open"
    class="fixed inset-0 z-50 flex items-center justify-center bg-black/80"
    @click.self="handleClose"
  >
    <div class="relative flex h-full w-full flex-col items-center justify-center md:h-auto md:max-h-[85vh] md:w-auto md:max-w-5xl">
      <button
        class="absolute right-4 top-4 z-10 rounded-full bg-black/50 p-2 text-white hover:bg-black/70"
        @click="handleClose"
      >
        <X class="h-5 w-5" />
      </button>

      <div
        v-if="loading"
        class="absolute inset-0 z-10 flex items-center justify-center text-white"
      >
        <LoaderCircle class="h-10 w-10 animate-spin" />
      </div>
      <div
        v-if="errorMsg"
        class="absolute inset-0 z-10 flex items-center justify-center text-sm text-red-300"
      >
        {{ errorMsg }}
      </div>

      <video
        ref="videoRef"
        class="max-h-[85vh] w-full bg-black md:max-w-5xl"
        controls
        playsinline
        crossorigin="use-credentials"
        @seeking="handleSeeking"
        @pause="reportProgress"
      >
        <track
          v-if="subtitleIndex !== null && item"
          kind="subtitles"
          :src="subtitleUrl(item.id, subtitleIndex)"
          default
        >
      </video>

      <div
        v-if="playbackInfo && (playbackInfo.audioTracks.length > 1 || playbackInfo.subtitleTracks.length > 0)"
        class="mt-3 flex items-center gap-4 text-sm text-white"
      >
        <label
          v-if="playbackInfo.audioTracks.length > 1"
          class="flex items-center gap-2"
        >
          音轨
          <select
            v-model="audioIndex"
            class="rounded-lg bg-surface-800 px-2 py-1 text-white"
            @change="handleAudioTrackChange"
          >
            <option
              v-for="track in playbackInfo.audioTracks"
              :key="track.index"
              :value="track.index"
            >
              {{ track.title || track.language || `音轨 ${track.index + 1}` }}（{{ track.codec }}）
            </option>
          </select>
        </label>
        <label
          v-if="playbackInfo.subtitleTracks.length > 0"
          class="flex items-center gap-2"
        >
          字幕
          <select
            v-model="subtitleIndex"
            class="rounded-lg bg-surface-800 px-2 py-1 text-white"
          >
            <option :value="null">
              关闭
            </option>
            <option
              v-for="track in playbackInfo.subtitleTracks"
              :key="track.index"
              :value="track.index"
            >
              {{ track.title || track.language || `字幕 ${track.index + 1}` }}
            </option>
          </select>
        </label>
      </div>
    </div>
  </div>
</template>
