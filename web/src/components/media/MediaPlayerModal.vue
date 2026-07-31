<script setup lang="ts">
/**
 * 媒体播放器弹窗
 * - 播放逻辑（直放/转码/续播/进度上报/音轨/字幕/码率）由 useMediaPlayback 承载
 */
import { onBeforeUnmount, ref, watch } from 'vue'
import { X, LoaderCircle } from '@lucide/vue'
import type { MediaItemVo } from '@/types/media'
import { useMediaPlayback } from '@/composables/useMediaPlayback'
import PlayerSettingsMenu from '@/components/media/PlayerSettingsMenu.vue'

interface Props {
  open: boolean
  item: MediaItemVo | null
}

const props = defineProps<Props>()
const emit = defineEmits<{
  close: []
}>()

const videoRef = ref<HTMLVideoElement | null>(null)

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

watch(
  () => props.open,
  async (open) => {
    if (open && props.item) {
      await start(props.item.id)
    } else {
      stop()
    }
  },
)

onBeforeUnmount(() => {
  stop()
})

function handleClose() {
  stop()
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
        v-if="playbackInfo"
        class="absolute right-16 top-4 z-10"
      >
        <PlayerSettingsMenu
          :playback-info="playbackInfo"
          :audio-index="audioIndex"
          :subtitle-key="subtitleKey"
          :bitrate-tier-key="bitrateTierKey"
          @select-audio="selectAudioTrack"
          @select-subtitle="selectSubtitle"
          @select-bitrate="selectBitrateTier"
        />
      </div>

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
  </div>
</template>
