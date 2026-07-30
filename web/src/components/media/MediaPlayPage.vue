<script setup lang="ts">
/**
 * 独立媒体播放页（无顶栏/侧栏，PC/移动端共用）
 * - 支持直放与 HLS 转码、续播、进度上报、音轨/字幕选择
 * - 电视剧显示选集列表，播完自动连播下一集
 */
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import Hls from 'hls.js'
import { ArrowLeft, ListVideo, LoaderCircle } from '@lucide/vue'
import type { MediaItemDetailVo, MediaItemVo, MediaPlaybackInfoVo } from '@/types/media'
import {
  createTranscodeSession,
  fetchItemDetail,
  fetchMediaEpisodes,
  fetchPlaybackInfo,
  subtitleUrl,
  updateMediaProgress,
  withToken,
} from '@/api/media'
import { cn } from '@/utils/cn'

const route = useRoute()
const router = useRouter()

const videoRef = ref<HTMLVideoElement | null>(null)
const loading = ref(true)
const errorMsg = ref('')
const playbackInfo = ref<MediaPlaybackInfoVo | null>(null)
const detail = ref<MediaItemDetailVo | null>(null)
const episodes = ref<MediaItemVo[]>([])
const episodePanelOpen = ref(false)
const audioIndex = ref<number | null>(null)
const subtitleIndex = ref<number | null>(null)

let hls: Hls | null = null
let progressTimer: ReturnType<typeof setInterval> | null = null
let destroyed = false

const itemId = computed(() => route.params.id as string)
const isEpisode = computed(() => detail.value?.itemType === 'episode')
const title = computed(() => {
  const d = detail.value
  if (!d) return ''
  if (d.itemType === 'episode' && d.seriesName) {
    const label = d.seasonNo != null && d.episodeNo != null
      ? ` S${String(d.seasonNo).padStart(2, '0')}E${String(d.episodeNo).padStart(2, '0')}`
      : ''
    return `${d.seriesName}${label}`
  }
  return d.title
})

onMounted(() => {
  destroyed = false
  void init(itemId.value)
})

watch(itemId, (id, oldId) => {
  if (id && id !== oldId) {
    void init(id)
  }
})

onBeforeUnmount(() => {
  destroyed = true
  teardown()
})

async function init(id: string) {
  teardown()
  loading.value = true
  errorMsg.value = ''
  try {
    detail.value = await fetchItemDetail(id)
    if (destroyed) return
    if (detail.value.itemType === 'episode' && detail.value.seriesId) {
      fetchMediaEpisodes(detail.value.seriesId)
        .then((list) => {
          if (!destroyed) episodes.value = list
        })
        .catch(() => {})
    } else {
      episodes.value = []
    }
    playbackInfo.value = await fetchPlaybackInfo(id)
    if (destroyed) return
    const queryStart = Number(route.query.startMs)
    const resumeMs = Number.isFinite(queryStart) && queryStart > 0
      ? queryStart
      : playbackInfo.value.progressMs ?? 0
    if (playbackInfo.value.mode === 'direct') {
      setupDirect(playbackInfo.value.directUrl!, resumeMs)
    } else {
      await setupTranscode(resumeMs)
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

function setupDirect(url: string, startMs: number) {
  destroyHls()
  const video = videoRef.value
  if (!video) return
  video.src = withToken(url)
  video.currentTime = startMs / 1000
  video.play().catch(() => {})
}

async function setupTranscode(startMs: number) {
  destroyHls()
  const session = await createTranscodeSession(itemId.value, startMs, audioIndex.value ?? undefined)
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
    hls.on(Hls.Events.ERROR, (_event, data) => {
      if (data.fatal) {
        errorMsg.value = data.error?.message ?? '转码播放失败'
      }
    })
  } else if (video.canPlayType('application/vnd.apple.mpegurl')) {
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
  // 卸载/切集时路由参数已变化，必须使用已加载条目的 id
  const id = detail.value?.id
  if (!video || !id || video.currentTime <= 0) return
  updateMediaProgress(id, Math.floor(video.currentTime * 1000)).catch(() => {})
}

function handleAudioTrackChange() {
  const video = videoRef.value
  const currentMs = video ? video.currentTime * 1000 : 0
  if (playbackInfo.value?.mode === 'transcode') {
    setupTranscode(currentMs).catch(() => {})
  }
}

// ---------- 选集与连播 ----------

const currentEpisodeIndex = computed(() => episodes.value.findIndex((e) => e.id === itemId.value))
const nextEpisode = computed(() => {
  const index = currentEpisodeIndex.value
  return index >= 0 && index < episodes.value.length - 1 ? episodes.value[index + 1] : null
})

function episodeLabel(episode: MediaItemVo): string {
  if (episode.seasonNo != null && episode.episodeNo != null) {
    return `S${String(episode.seasonNo).padStart(2, '0')}E${String(episode.episodeNo).padStart(2, '0')}`
  }
  return '未知集'
}

function switchEpisode(episode: MediaItemVo) {
  reportProgress()
  episodePanelOpen.value = false
  router.replace({ name: 'MediaPlay', params: { id: episode.id }, query: {} })
}

function handleEnded() {
  if (nextEpisode.value) {
    switchEpisode(nextEpisode.value)
  }
}

// ---------- 生命周期 ----------

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
  detail.value = null
  audioIndex.value = null
  subtitleIndex.value = null
}

function handleBack() {
  router.back()
}
</script>

<template>
  <div class="flex h-screen w-screen flex-col bg-black">
    <!-- 顶栏：返回 + 标题 -->
    <div class="flex shrink-0 items-center gap-3 px-4 py-3 text-white">
      <button
        class="rounded-full bg-white/10 p-2 hover:bg-white/20"
        title="返回"
        @click="handleBack"
      >
        <ArrowLeft class="h-5 w-5" />
      </button>
      <p class="min-w-0 flex-1 truncate text-sm font-medium">
        {{ title }}
      </p>
      <button
        v-if="isEpisode"
        :class="cn('rounded-full p-2 hover:bg-white/20', episodePanelOpen ? 'bg-white/25' : 'bg-white/10')"
        title="选集"
        @click="episodePanelOpen = !episodePanelOpen"
      >
        <ListVideo class="h-5 w-5" />
      </button>
    </div>

    <div class="flex min-h-0 flex-1">
      <!-- 播放区 -->
      <div class="relative flex min-w-0 flex-1 flex-col items-center justify-center">
        <div
          v-if="loading"
          class="absolute inset-0 z-10 flex items-center justify-center text-white"
        >
          <LoaderCircle class="h-10 w-10 animate-spin" />
        </div>
        <div
          v-if="errorMsg"
          class="absolute inset-0 z-10 flex items-center justify-center px-8 text-center text-sm text-red-300"
        >
          {{ errorMsg }}
        </div>

        <video
          ref="videoRef"
          class="max-h-full w-full bg-black"
          controls
          playsinline
          crossorigin="use-credentials"
          @seeking="handleSeeking"
          @pause="reportProgress"
          @ended="handleEnded"
        >
          <track
            v-if="subtitleIndex !== null"
            kind="subtitles"
            :src="subtitleUrl(itemId, subtitleIndex)"
            default
          >
        </video>

        <div
          v-if="playbackInfo && (playbackInfo.audioTracks.length > 1 || playbackInfo.subtitleTracks.length > 0)"
          class="flex shrink-0 items-center gap-4 py-2 text-sm text-white"
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

      <!-- 选集面板 -->
      <div
        v-if="isEpisode && episodePanelOpen"
        class="w-56 shrink-0 overflow-y-auto border-l border-white/10 bg-surface-950 md:w-64"
      >
        <p class="px-4 py-3 text-xs font-semibold text-surface-400">
          选集（{{ episodes.length }}）
        </p>
        <button
          v-for="episode in episodes"
          :key="episode.id"
          :class="cn(
            'flex w-full items-center gap-2 px-4 py-2.5 text-left text-sm transition-colors',
            episode.id === itemId ? 'bg-primary-600/20 text-primary-300' : 'text-surface-300 hover:bg-white/5'
          )"
          @click="switchEpisode(episode)"
        >
          <span class="shrink-0 rounded bg-white/10 px-1.5 py-0.5 text-xs font-semibold">
            {{ episodeLabel(episode) }}
          </span>
          <span class="truncate">{{ episode.fileName }}</span>
        </button>
      </div>
    </div>
  </div>
</template>
