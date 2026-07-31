<script setup lang="ts">
/**
 * 独立媒体播放页（无顶栏/侧栏，PC/移动端共用）
 * - 播放逻辑（直放/转码/续播/进度上报/音轨/字幕/码率）由 useMediaPlayback 承载
 * - 电视剧显示选集列表，播完自动连播下一集
 */
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowLeft, ListVideo, LoaderCircle } from '@lucide/vue'
import type { MediaItemDetailVo, MediaItemVo } from '@/types/media'
import { fetchItemDetail, fetchMediaEpisodes } from '@/api/media'
import { useMediaPlayback } from '@/composables/useMediaPlayback'
import PlayerSettingsMenu from '@/components/media/PlayerSettingsMenu.vue'
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
  audioIndex,
  subtitleKey,
  bitrateTierKey,
  activeSubtitle,
  sourceEpoch,
  start,
  stop,
  handleSeeking,
  reportProgress,
  handleTrackLoad,
  selectAudioTrack,
  selectSubtitle,
  selectBitrateTier,
} = useMediaPlayback(videoRef)

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
  await start(id, startMs)
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

function handleBack() {
  router.back()
}
</script>

<template>
  <div class="flex h-screen w-screen flex-col bg-black">
    <!-- 顶栏：返回 + 标题 + 设置 + 选集 -->
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
      <PlayerSettingsMenu
        v-if="playbackInfo"
        :playback-info="playbackInfo"
        :audio-index="audioIndex"
        :subtitle-key="subtitleKey"
        :bitrate-tier-key="bitrateTierKey"
        @select-audio="selectAudioTrack"
        @select-subtitle="selectSubtitle"
        @select-bitrate="selectBitrateTier"
      />
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
            v-if="activeSubtitle"
            :key="`${activeSubtitle.key}:${sourceEpoch}`"
            kind="subtitles"
            :src="activeSubtitle.src"
            :label="activeSubtitle.label"
            default
            @load="handleTrackLoad"
          >
        </video>
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
