<script setup lang="ts">
/**
 * 独立媒体播放页（无顶栏/侧栏，PC/移动端共用）
 * - 播放逻辑（直放/转码/续播/进度上报/音轨/字幕/码率）由 useMediaPlayback 承载
 * - 控制栏状态与交互（播放/进度/音量/倍速/画中画/全屏/自动隐藏/快捷键）由 usePlayerControls 承载
 * - 视频区域从首帧起即撑满全屏，加载中为纯黑 + 居中加载圈
 * - 电视剧显示选集列表，播完自动连播下一集
 */
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowLeft, LoaderCircle } from '@lucide/vue'
import type { MediaItemDetailVo, MediaItemVo } from '@/types/media'
import { fetchItemDetail, fetchMediaEpisodes } from '@/api/media'
import { useMediaPlayback } from '@/composables/useMediaPlayback'
import { usePlayerControls, type PlayerTimeline } from '@/composables/usePlayerControls'
import PlayerControlBar from '@/components/media/PlayerControlBar.vue'
import { cn } from '@/utils/cn'

const route = useRoute()
const router = useRouter()

const videoRef = ref<HTMLVideoElement | null>(null)
const detail = ref<MediaItemDetailVo | null>(null)
const episodes = ref<MediaItemVo[]>([])
const episodePanelOpen = ref(false)

const {
  loading,
  errorMsg,
  playbackInfo,
  currentVersionId,
  audioIndex,
  subtitleKey,
  bitrateTierKey,
  activeSubtitle,
  sourceEpoch,
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
} = useMediaPlayback(videoRef)

// 选集面板打开时钉住控制栏，不自动隐藏
const pinned = computed(() => episodePanelOpen.value)

/** 绝对时间轴：转码时纠正控制栏的当前时间/时长/缓冲显示（video.duration 随分片增长不可信） */
const timeline = computed<PlayerTimeline>(() => ({
  transcodeActive: transcodeActive.value,
  baseMs: transcodeBaseMs.value,
  durationMs: playbackInfo.value?.durationMs != null ? Number(playbackInfo.value.durationMs) : null,
}))

const controls = usePlayerControls(videoRef, pinned, timeline)
const { controlsVisible, wake, toggleControls } = controls

const itemId = computed(() => route.params.id as string)
const isEpisode = computed(() => detail.value?.itemType === 'episode')
/** 电影版本列表（仅电影有效），多于 1 个时播放页内可切换版本 */
const movieVersions = computed(() => detail.value?.versions ?? [])
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
  void init(itemId.value)
})

watch(itemId, (id, oldId) => {
  if (id && id !== oldId) {
    void init(id)
  }
})

onBeforeUnmount(() => {
  stop()
})

async function init(id: string) {
  try {
    detail.value = await fetchItemDetail(id)
    if (detail.value.itemType === 'episode' && detail.value.seriesId) {
      fetchMediaEpisodes(detail.value.seriesId)
        .then((list) => {
          episodes.value = list
        })
        .catch(() => {})
    } else {
      episodes.value = []
    }
  } catch {
    detail.value = null
    episodes.value = []
  }
  const queryStart = Number(route.query.startMs)
  const startMs = Number.isFinite(queryStart) && queryStart > 0 ? queryStart : undefined
  await start(id, startMs, versionIdFromQuery())
}

/** 路由 query 中的电影版本 ID（详情页点击版本进入时携带） */
function versionIdFromQuery(): string | undefined {
  const raw = route.query.versionId
  return typeof raw === 'string' && raw ? raw : undefined
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

/** 进度条拖拽跳转：绝对秒数，转码超缓冲时由 seekToAbsolute 重建会话 */
function handleSeek(seconds: number) {
  seekToAbsolute(seconds).catch((e) => {
    errorMsg.value = e instanceof Error ? e.message : '跳转失败'
  })
}

function handleBack() {
  router.back()
}
</script>

<template>
  <div
    :class="cn(
      'relative h-screen w-screen select-none overflow-hidden bg-black',
      !controlsVisible && 'cursor-none'
    )"
    @mousemove="wake"
    @touchstart="wake"
  >
    <!-- 视频：从首帧起撑满全屏，object-contain 保持比例居中 -->
    <video
      ref="videoRef"
      class="absolute inset-0 h-full w-full object-contain"
      playsinline
      crossorigin="use-credentials"
      @click="toggleControls"
      @seeking="handleSeeking"
      @pause="reportProgress"
      @ended="handleEnded"
    >
      <track
        v-if="activeSubtitle"
        :key="`${activeSubtitle.key}:${sourceEpoch}`"
        kind="subtitles"
        :src="activeSubtitle.src"
        :label="activeSubtitle.label"
        default
        @load="handleTrackLoad"
      >
    </video>

    <!-- 加载中：纯黑全屏 + 居中加载圈 -->
    <div
      v-if="loading"
      class="pointer-events-none absolute inset-0 z-10 flex items-center justify-center text-white"
    >
      <LoaderCircle class="h-10 w-10 animate-spin" />
    </div>
    <div
      v-if="errorMsg"
      class="absolute inset-0 z-10 flex items-center justify-center px-8 text-center text-sm text-red-300"
    >
      {{ errorMsg }}
    </div>

    <!-- 顶栏：返回 + 标题 -->
    <div
      :class="cn(
        'absolute inset-x-0 top-0 z-20 flex items-center gap-3 bg-gradient-to-b from-black/70 to-transparent px-4 pb-8 pt-3 text-white transition-opacity duration-300',
        controlsVisible ? 'opacity-100' : 'pointer-events-none opacity-0'
      )"
    >
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
    </div>

    <!-- 底部控制栏 -->
    <PlayerControlBar
      :controls="controls"
      :playback-info="playbackInfo"
      :audio-index="audioIndex"
      :subtitle-key="subtitleKey"
      :bitrate-tier-key="bitrateTierKey"
      :is-episode="isEpisode"
      :episode-panel-open="episodePanelOpen"
      :versions="movieVersions"
      :current-version-id="currentVersionId"
      @select-audio="selectAudioTrack"
      @select-subtitle="selectSubtitle"
      @select-bitrate="selectBitrateTier"
      @select-version="selectVersion"
      @toggle-episode-panel="episodePanelOpen = !episodePanelOpen"
      @seek="handleSeek"
    />

    <!-- 选集面板 -->
    <div
      v-if="isEpisode && episodePanelOpen"
      class="absolute inset-y-0 right-0 z-30 w-56 overflow-y-auto border-l border-white/10 bg-surface-950/95 backdrop-blur md:w-64"
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
</template>
