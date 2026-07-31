<script setup lang="ts">
/**
 * 播放器设置菜单（齿轮按钮 + 浮层）
 * - 码率档位 / 音轨 / 字幕 三组单选列表，当前选中态高亮
 * - 音轨 >1 条、字幕 ≥1 条时才显示对应分组
 */
import { computed } from 'vue'
import {
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuRoot,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from 'radix-vue'
import { Check, Settings } from '@lucide/vue'
import type { MediaPlaybackInfoVo, MediaTrack } from '@/types/media'
import { BITRATE_TIERS, subtitleItemKey } from '@/composables/useMediaPlayback'
import { cn } from '@/utils/cn'

interface Props {
  playbackInfo: MediaPlaybackInfoVo
  audioIndex: number | null
  subtitleKey: string | null
  bitrateTierKey: string
}

const props = defineProps<Props>()
const emit = defineEmits<{
  selectAudio: [index: number | null]
  selectSubtitle: [key: string | null]
  selectBitrate: [key: string]
}>()

const showAudioGroup = computed(() => props.playbackInfo.audioTracks.length > 1)
const showSubtitleGroup = computed(() => props.playbackInfo.subtitles.length >= 1)

interface MenuOption {
  key: string | null
  label: string
}

const audioOptions = computed<MenuOption[]>(() => [
  { key: null, label: '默认' },
  ...props.playbackInfo.audioTracks.map((track) => ({
    key: String(track.index),
    label: audioTrackLabel(track),
  })),
])

const subtitleOptions = computed<MenuOption[]>(() => [
  { key: null, label: '无' },
  ...props.playbackInfo.subtitles.map((item) => ({
    key: subtitleItemKey(item),
    label: item.label,
  })),
])

function audioTrackLabel(track: MediaTrack): string {
  return `${track.title || track.language || `音轨 ${track.index + 1}`}（${track.codec}）`
}

function handleSelectAudio(key: string | null) {
  emit('selectAudio', key === null ? null : Number(key))
}

const itemClass = 'flex cursor-pointer items-center gap-2 rounded-lg px-3 py-2 text-sm text-surface-200 outline-none transition-colors hover:bg-white/10 focus:bg-white/10'
const groupLabelClass = 'px-3 pb-1 pt-2 text-xs font-semibold text-surface-400'
</script>

<template>
  <DropdownMenuRoot>
    <DropdownMenuTrigger as-child>
      <button
        class="rounded-full bg-white/10 p-2 text-white hover:bg-white/20"
        title="播放设置"
      >
        <Settings class="h-5 w-5" />
      </button>
    </DropdownMenuTrigger>

    <DropdownMenuContent
      side="bottom"
      align="end"
      :side-offset="8"
      class="z-50 max-h-[70vh] min-w-[180px] overflow-y-auto rounded-xl border border-white/10 bg-surface-950/95 p-1.5 shadow-lg backdrop-blur"
    >
      <!-- 码率 -->
      <p :class="groupLabelClass">
        码率
      </p>
      <DropdownMenuItem
        v-for="tier in BITRATE_TIERS"
        :key="tier.key"
        :class="cn(itemClass, tier.key === bitrateTierKey && 'text-primary-300')"
        @click="emit('selectBitrate', tier.key)"
      >
        <Check :class="cn('h-4 w-4 shrink-0', tier.key === bitrateTierKey ? 'opacity-100' : 'opacity-0')" />
        {{ tier.label }}
      </DropdownMenuItem>

      <template v-if="showAudioGroup">
        <DropdownMenuSeparator class="my-1.5 h-px bg-white/10" />
        <p :class="groupLabelClass">
          音轨
        </p>
        <DropdownMenuItem
          v-for="option in audioOptions"
          :key="option.key ?? 'default'"
          :class="cn(itemClass, option.key === (audioIndex === null ? null : String(audioIndex)) && 'text-primary-300')"
          @click="handleSelectAudio(option.key)"
        >
          <Check
            :class="cn(
              'h-4 w-4 shrink-0',
              option.key === (audioIndex === null ? null : String(audioIndex)) ? 'opacity-100' : 'opacity-0'
            )"
          />
          <span class="truncate">{{ option.label }}</span>
        </DropdownMenuItem>
      </template>

      <template v-if="showSubtitleGroup">
        <DropdownMenuSeparator class="my-1.5 h-px bg-white/10" />
        <p :class="groupLabelClass">
          字幕
        </p>
        <DropdownMenuItem
          v-for="option in subtitleOptions"
          :key="option.key ?? 'none'"
          :class="cn(itemClass, option.key === subtitleKey && 'text-primary-300')"
          @click="emit('selectSubtitle', option.key)"
        >
          <Check :class="cn('h-4 w-4 shrink-0', option.key === subtitleKey ? 'opacity-100' : 'opacity-0')" />
          <span class="truncate">{{ option.label }}</span>
        </DropdownMenuItem>
      </template>
    </DropdownMenuContent>
  </DropdownMenuRoot>
</template>
