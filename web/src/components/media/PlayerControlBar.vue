<script setup lang="ts">
/**
 * 播放器底部控制栏（Jellyfin 风两排式，替代原生 video controls）
 * - 上排：整条进度条（含缓冲区，可点击/拖拽）+ 时间显示（当前/总长）
 * - 下排：播放/暂停、音量、倍速、字幕、码率、音轨、画中画、选集、全屏
 * - 拖拽进度时本地显示拖拽位置，松手才 emit 绝对秒数 seek，避免与 timeupdate 互相跳变
 * - 倍速/字幕/码率/音轨为独立按钮 + PlayerOptionMenu 弹层，任一弹层打开钉住控制栏
 * - 移动端（md 以下）隐藏音量与画中画按钮
 */
import { computed, ref } from 'vue'
import {
  AudioLines,
  Captions,
  Gauge,
  ListVideo,
  Maximize,
  Minimize,
  Pause,
  PictureInPicture2,
  Play,
  Volume2,
  VolumeX,
} from '@lucide/vue'
import type { MediaPlaybackInfoVo, MediaTrack } from '@/types/media'
import type { PlayerControls } from '@/composables/usePlayerControls'
import { SPEED_OPTIONS } from '@/composables/usePlayerControls'
import { BITRATE_TIERS, subtitleItemKey } from '@/composables/useMediaPlayback'
import PlayerOptionMenu from '@/components/media/PlayerOptionMenu.vue'
import { cn } from '@/utils/cn'

interface Props {
  controls: PlayerControls
  playbackInfo: MediaPlaybackInfoVo | null
  audioIndex: number | null
  subtitleKey: string | null
  bitrateTierKey: string
  isEpisode: boolean
  episodePanelOpen: boolean
}

const props = defineProps<Props>()
const emit = defineEmits<{
  selectAudio: [index: number | null]
  selectSubtitle: [key: string | null]
  selectBitrate: [key: string]
  toggleEpisodePanel: []
  /** 拖拽/点击进度条跳转，绝对秒数（含转码偏移） */
  seek: [seconds: number]
}>()

const {
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
  controlsVisible,
  togglePlay,
  setVolume,
  toggleMute,
  toggleFullscreen,
  togglePip,
} = props.controls

// ---------- 进度条拖拽 ----------

const dragging = ref(false)
const dragTime = ref(0)

const displayTime = computed(() => (dragging.value ? dragTime.value : currentTime.value))
const progressPercent = computed(() => (duration.value > 0 ? (displayTime.value / duration.value) * 100 : 0))
const bufferedPercent = computed(() => {
  if (duration.value <= 0) return 0
  return Math.min((bufferedEnd.value / duration.value) * 100, 100)
})

function updateDrag(event: PointerEvent) {
  const bar = event.currentTarget as HTMLElement
  const rect = bar.getBoundingClientRect()
  const ratio = Math.min(Math.max((event.clientX - rect.left) / rect.width, 0), 1)
  dragTime.value = ratio * duration.value
}

function onPointerDown(event: PointerEvent) {
  if (duration.value <= 0) return
  dragging.value = true
  ;(event.currentTarget as HTMLElement).setPointerCapture(event.pointerId)
  updateDrag(event)
}

function onPointerMove(event: PointerEvent) {
  if (dragging.value) updateDrag(event)
}

function onPointerUp(event: PointerEvent) {
  if (!dragging.value) return
  updateDrag(event)
  emit('seek', dragTime.value)
  dragging.value = false
}

function onPointerCancel() {
  dragging.value = false
}

// ---------- 弹层选项 ----------

interface MenuOption {
  key: string
  label: string
  checked: boolean
}

const hasSubtitles = computed(() => (props.playbackInfo?.subtitles.length ?? 0) > 0)
const hasMultipleAudio = computed(() => (props.playbackInfo?.audioTracks.length ?? 0) > 1)

const speedOptions = computed<MenuOption[]>(() => SPEED_OPTIONS.map((rate) => ({
  key: String(rate),
  label: `${rate}x`,
  checked: rate === playbackRate.value,
})))

const subtitleOptions = computed<MenuOption[]>(() => [
  { key: 'none', label: '无', checked: props.subtitleKey === null },
  ...(props.playbackInfo?.subtitles ?? []).map((item) => ({
    key: subtitleItemKey(item),
    label: item.label,
    checked: subtitleItemKey(item) === props.subtitleKey,
  })),
])

const bitrateOptions = computed<MenuOption[]>(() => BITRATE_TIERS.map((tier) => ({
  key: tier.key,
  label: tier.label,
  checked: tier.key === props.bitrateTierKey,
})))

const audioOptions = computed<MenuOption[]>(() => [
  { key: 'default', label: '默认', checked: props.audioIndex === null },
  ...(props.playbackInfo?.audioTracks ?? []).map((track) => ({
    key: String(track.index),
    label: audioTrackLabel(track),
    checked: track.index === props.audioIndex,
  })),
])

function audioTrackLabel(track: MediaTrack): string {
  return `${track.title || track.language || `音轨 ${track.index + 1}`}（${track.codec}）`
}

// ---------- 音量 ----------

function onVolumeInput(event: Event) {
  setVolume(Number((event.target as HTMLInputElement).value))
}

// ---------- 时间格式化 ----------

function formatTime(seconds: number): string {
  const total = Number.isFinite(seconds) && seconds > 0 ? Math.floor(seconds) : 0
  const h = Math.floor(total / 3600)
  const m = Math.floor((total % 3600) / 60)
  const s = total % 60
  const ss = String(s).padStart(2, '0')
  if (h > 0) return `${h}:${String(m).padStart(2, '0')}:${ss}`
  return `${m}:${ss}`
}

const buttonClass = 'rounded-full p-2 text-white transition-colors hover:bg-white/20'
</script>

<template>
  <div
    :class="cn(
      'absolute inset-x-0 bottom-0 z-20 bg-gradient-to-t from-black/80 via-black/40 to-transparent px-3 pb-2.5 pt-10 transition-opacity duration-300 md:px-4',
      controlsVisible ? 'opacity-100' : 'pointer-events-none opacity-0'
    )"
  >
    <!-- 上排：整条进度条 + 时间 -->
    <div class="flex items-center gap-3">
      <div
        class="group relative flex h-5 min-w-0 flex-1 cursor-pointer touch-none items-center"
        @pointerdown="onPointerDown"
        @pointermove="onPointerMove"
        @pointerup="onPointerUp"
        @pointercancel="onPointerCancel"
      >
        <div class="relative h-1 w-full rounded-full bg-white/20 transition-[height] group-hover:h-1.5">
          <div
            class="absolute inset-y-0 left-0 rounded-full bg-white/25"
            :style="{ width: `${bufferedPercent}%` }"
          />
          <div
            class="absolute inset-y-0 left-0 rounded-full bg-primary-500"
            :style="{ width: `${progressPercent}%` }"
          />
        </div>
      </div>
      <span class="hidden shrink-0 text-xs tabular-nums text-white/80 sm:inline">
        {{ formatTime(displayTime) }} / {{ formatTime(duration) }}
      </span>
    </div>

    <!-- 下排：操作按钮 -->
    <div class="mt-1 flex items-center gap-1 md:gap-2">
      <!-- 播放/暂停 -->
      <button :class="buttonClass" :title="playing ? '暂停' : '播放'" @click="togglePlay">
        <Pause v-if="playing" class="h-5 w-5" />
        <Play v-else class="h-5 w-5" />
      </button>

      <!-- 音量：静音切换 + hover 展开滑条（移动端隐藏） -->
      <div class="group/vol flex items-center max-md:hidden">
        <button
          :class="buttonClass"
          :title="muted || volume === 0 ? '取消静音' : '静音'"
          @click="toggleMute"
        >
          <VolumeX v-if="muted || volume === 0" class="h-5 w-5" />
          <Volume2 v-else class="h-5 w-5" />
        </button>
        <div class="w-0 overflow-hidden transition-[width] duration-200 group-hover/vol:w-20">
          <input
            type="range"
            min="0"
            max="1"
            step="0.05"
            :value="muted ? 0 : volume"
            class="h-1 w-20 cursor-pointer accent-white"
            title="音量"
            @input="onVolumeInput"
          >
        </div>
      </div>

      <!-- 倍速 -->
      <PlayerOptionMenu
        v-model:open="rateMenuOpen"
        title="倍速"
        :options="speedOptions"
        @select="props.controls.setRate(Number($event))"
      >
        <button
          class="w-11 shrink-0 rounded-full py-2 text-center text-xs font-semibold text-white transition-colors hover:bg-white/20"
          title="倍速"
        >
          {{ playbackRate }}x
        </button>
      </PlayerOptionMenu>

      <!-- 字幕（无字幕时隐藏） -->
      <PlayerOptionMenu
        v-if="playbackInfo && hasSubtitles"
        v-model:open="subtitleMenuOpen"
        title="字幕"
        :options="subtitleOptions"
        @select="emit('selectSubtitle', $event === 'none' ? null : $event)"
      >
        <button :class="buttonClass" title="字幕">
          <Captions class="h-5 w-5" />
        </button>
      </PlayerOptionMenu>

      <!-- 码率 -->
      <PlayerOptionMenu
        v-if="playbackInfo"
        v-model:open="bitrateMenuOpen"
        title="码率"
        :options="bitrateOptions"
        @select="emit('selectBitrate', $event)"
      >
        <button :class="buttonClass" title="码率">
          <Gauge class="h-5 w-5" />
        </button>
      </PlayerOptionMenu>

      <!-- 音轨（仅多音轨时显示） -->
      <PlayerOptionMenu
        v-if="playbackInfo && hasMultipleAudio"
        v-model:open="audioMenuOpen"
        title="音轨"
        :options="audioOptions"
        @select="emit('selectAudio', $event === 'default' ? null : Number($event))"
      >
        <button :class="buttonClass" title="音轨">
          <AudioLines class="h-5 w-5" />
        </button>
      </PlayerOptionMenu>

      <!-- 画中画（移动端隐藏） -->
      <button
        v-if="pipSupported"
        :class="cn(buttonClass, 'max-md:hidden')"
        title="画中画"
        @click="togglePip"
      >
        <PictureInPicture2 class="h-5 w-5" />
      </button>

      <!-- 选集（仅剧集） -->
      <button
        v-if="isEpisode"
        :class="cn(buttonClass, episodePanelOpen && 'bg-white/25')"
        title="选集"
        @click="emit('toggleEpisodePanel')"
      >
        <ListVideo class="h-5 w-5" />
      </button>

      <!-- 全屏切换 -->
      <button
        :class="buttonClass"
        :title="isFullscreen ? '退出全屏' : '全屏'"
        @click="toggleFullscreen"
      >
        <Minimize v-if="isFullscreen" class="h-5 w-5" />
        <Maximize v-else class="h-5 w-5" />
      </button>
    </div>
  </div>
</template>
