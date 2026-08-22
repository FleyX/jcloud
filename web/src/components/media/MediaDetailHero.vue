<script setup lang="ts">
/**
 * 媒体详情页头部（Jellyfin 风格沉浸两栏）
 * 顶部为加高的 backdrop 背景图，底部渐变融入页面底色（明暗主题各自自然）；
 * 下方主体桌面端（≥md）为 1:2 两栏：左栏仅海报（撑满栏宽、2:3 比例、上探量为背景横幅高度的 1/4），
 * 右栏依次为标题/元信息/操作按钮/简介，并在简介之下开放默认插槽承载页面级内容；
 * <md 时纵向堆叠（整页统一滚动、左栏不吸顶）。
 */
import { computed, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import {
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuPortal,
  DropdownMenuRoot,
  DropdownMenuTrigger,
} from 'radix-vue'
import { ArrowLeft, Check, Film, Heart, Pencil, Play, RefreshCcw, RefreshCw, RotateCcw, Star } from '@lucide/vue'
import { formatDurationText, formatPosition } from './format'
import { cn } from '@/utils/cn'

interface Props {
  title: string
  originalTitle?: string | null
  backdropUrl?: string | null
  posterUrl?: string | null
  releaseDate?: string | null
  voteAverage?: number | null
  genres?: string[]
  durationMs?: number | null
  overview?: string | null
  unmatched?: boolean
  /** 续播位置（毫秒），>0 时显示「继续播放 + 从头播放」 */
  continueMs?: number
  /** 文件信息标签（未识别简版详情用） */
  fileInfoChips?: string[]
  /** 是否显示刷新元数据按钮 */
  showRefresh?: boolean
  /** 是否已收藏（显示收藏按钮） */
  favorited?: boolean
  /** 是否已观看（显示标记已观看/未观看按钮） */
  watched?: boolean
}

const props = withDefaults(defineProps<Props>(), {
  genres: () => [],
  fileInfoChips: () => [],
  continueMs: 0,
  favorited: false,
  watched: false,
})

const emit = defineEmits<{
  play: [startMs: number]
  rematch: []
  refresh: [mode: 'missing' | 'force']
  'toggle-favorite': []
  'toggle-watched': []
}>()

/** 刷新菜单是否展开（radix-vue DropdownMenu 受控） */
const refreshOpen = ref(false)

/** 刷新菜单项：刷新缺失元数据 / 强制刷新元数据 */
const refreshActions = [
  { mode: 'missing' as const, label: '刷新缺失元数据', icon: RefreshCw },
  { mode: 'force' as const, label: '强制刷新元数据', icon: RefreshCcw },
]

function handleRefresh(mode: 'missing' | 'force') {
  refreshOpen.value = false
  emit('refresh', mode)
}

const yearText = computed(() => (props.releaseDate ? props.releaseDate.slice(0, 4) : null))
const durationText = computed(() => formatDurationText(props.durationMs))
const ratingText = computed(() =>
  props.voteAverage != null && props.voteAverage > 0 ? props.voteAverage.toFixed(1) : null,
)

const router = useRouter()

/** 背景图/海报加载失败标志：加载失败视同无图（背景保留渐变底、海报走 v-else 占位），URL 变化时复位 */
const backdropError = ref(false)
const posterError = ref(false)
watch(
  () => props.backdropUrl,
  () => {
    backdropError.value = false
  },
)
watch(
  () => props.posterUrl,
  () => {
    posterError.value = false
  },
)

/** 返回上一页，无历史时回影视首页 */
function goBack() {
  if (window.history.state?.back) {
    router.back()
  } else {
    router.push('/media')
  }
}
</script>

<template>
  <div>
    <!-- 背景横幅（纯视觉：backdrop 加高，底部渐变融入页面底色 + 返回按钮，无海报与文字） -->
    <div class="relative h-64 overflow-hidden bg-surface-900 md:h-96 lg:h-[26rem]">
      <img
        v-if="backdropUrl && !backdropError"
        :src="backdropUrl"
        :alt="title"
        class="h-full w-full object-cover"
        @error="backdropError = true"
      >
      <div class="absolute inset-0 bg-gradient-to-t from-pagebg via-pagebg/30 to-black/20" />

      <!-- 返回按钮 -->
      <button
        class="absolute left-4 top-4 z-10 flex h-9 w-9 items-center justify-center rounded-full bg-black/30 text-white backdrop-blur-sm transition-colors hover:bg-black/50 md:left-6 md:top-6"
        title="返回"
        @click="goBack"
      >
        <ArrowLeft class="h-5 w-5" />
      </button>
    </div>

    <!-- 主体：桌面端 1:2 两栏（左海报右内容，grid-cols-[1fr_2fr]），<md 纵向堆叠；整行上探量 = 背景横幅高度的 1/4（横幅 h-64/md:h-96/lg:h-[26rem] → -mt-16/md:-mt-24/lg:-mt-[6.5rem]），与屏宽脱钩、各断点侵入比例一致；relative 使上探部分压在背景渐变遮罩之上 -->
    <div class="relative -mt-16 flex flex-col items-start gap-5 px-4 pb-10 md:grid md:grid-cols-[1fr_2fr] md:items-start md:gap-10 md:px-10 md:-mt-24 lg:-mt-[6.5rem]">
      <!-- 左栏：大海报（桌面端宽占左栏 80%，水平居中） -->
      <div class="aspect-[2/3] w-32 shrink-0 self-center overflow-hidden rounded-2xl bg-surface-200 shadow-xl ring-1 ring-white/20 md:w-4/5 md:self-auto md:justify-self-center">
        <img
          v-if="posterUrl && !posterError"
          :src="posterUrl"
          :alt="title"
          class="h-full w-full object-cover"
          @error="posterError = true"
        >
        <div
          v-else
          class="flex h-full w-full items-center justify-center text-surface-400"
        >
          <Film class="h-12 w-12" />
        </div>
      </div>

      <!-- 右栏：内容（grid 列内无需 flex-1，min-w-0 保证长内容不撑破 2fr 列；md 起顶部留约 3 行空白，与海报顶部错开） -->
      <div class="min-w-0 md:pt-20">
        <div class="flex items-center gap-2">
          <h1 class="truncate text-2xl font-bold text-surface-900 md:text-3xl">
            {{ title }}
          </h1>
          <span
            v-if="unmatched"
            class="shrink-0 rounded-lg bg-amber-500/90 px-1.5 py-0.5 text-xs font-medium text-white"
          >
            未识别
          </span>
        </div>
        <p
          v-if="originalTitle && originalTitle !== title"
          class="mt-0.5 truncate text-sm text-surface-500"
        >
          {{ originalTitle }}
        </p>

        <!-- 元信息行 -->
        <div class="mt-2 flex flex-wrap items-center gap-x-3 gap-y-1 text-sm text-surface-600">
          <span
            v-if="ratingText"
            class="flex items-center gap-1 font-semibold text-amber-500"
          >
            <Star class="h-4 w-4 fill-amber-500" />{{ ratingText }}
          </span>
          <span v-if="yearText">{{ yearText }}</span>
          <span v-if="durationText">{{ durationText }}</span>
          <span
            v-for="genre in genres"
            :key="genre"
            class="rounded-lg bg-surface-100 px-2 py-0.5 text-xs text-surface-600"
          >
            {{ genre }}
          </span>
          <span
            v-for="chip in fileInfoChips"
            :key="chip"
            class="rounded-lg bg-surface-100 px-2 py-0.5 text-xs text-surface-600"
          >
            {{ chip }}
          </span>
        </div>

        <!-- 操作按钮组：主播放键保留文字，其余仅图标 + 悬浮提示 -->
        <div class="mt-4 flex flex-wrap items-center gap-2">
          <button
            class="flex items-center gap-1.5 rounded-xl bg-primary-600 px-5 py-2 text-sm font-semibold text-white hover:bg-primary-700"
            @click="emit('play', continueMs > 0 ? continueMs : 0)"
          >
            <Play class="h-4 w-4 fill-white" />
            {{ continueMs > 0 ? `继续播放 (${formatPosition(continueMs)})` : '播放' }}
          </button>
          <button
            v-if="continueMs > 0"
            class="flex items-center rounded-xl bg-surface-100 px-3 py-2 text-sm text-surface-700 hover:bg-surface-200"
            title="从头播放"
            @click="emit('play', 0)"
          >
            <RotateCcw class="h-4 w-4" />
          </button>
          <button
            class="flex items-center rounded-xl bg-surface-100 px-3 py-2 text-sm text-surface-700 hover:bg-surface-200"
            title="修正匹配"
            @click="emit('rematch')"
          >
            <Pencil class="h-4 w-4" />
          </button>
          <button
            class="flex items-center rounded-xl px-3 py-2 text-sm transition-colors"
            :class="cn(
              favorited
                ? 'bg-rose-100 text-rose-600 hover:bg-rose-200'
                : 'bg-surface-100 text-surface-700 hover:bg-surface-200'
            )"
            :title="favorited ? '取消收藏' : '收藏'"
            @click="emit('toggle-favorite')"
          >
            <Heart
              class="h-4 w-4"
              :class="favorited && 'fill-rose-500'"
            />
          </button>
          <button
            class="flex items-center rounded-xl px-3 py-2 text-sm transition-colors"
            :class="cn(
              watched
                ? 'bg-emerald-100 text-emerald-600 hover:bg-emerald-200'
                : 'bg-surface-100 text-surface-700 hover:bg-surface-200'
            )"
            :title="watched ? '标记未观看' : '标记已观看'"
            @click="emit('toggle-watched')"
          >
            <Check
              class="h-4 w-4"
              :class="watched && 'fill-emerald-600'"
            />
          </button>
          <DropdownMenuRoot
            v-if="showRefresh"
            v-model:open="refreshOpen"
          >
            <DropdownMenuTrigger as-child>
              <button
                class="flex items-center gap-1.5 rounded-xl bg-surface-100 px-3 py-2 text-sm text-surface-700 hover:bg-surface-200"
                title="刷新元数据"
              >
                <RefreshCw class="h-4 w-4" />
              </button>
            </DropdownMenuTrigger>
            <DropdownMenuPortal>
              <DropdownMenuContent
                align="end"
                :side-offset="6"
                class="z-50 min-w-[160px] overflow-hidden rounded-xl border border-white/20 bg-white p-1.5 shadow-soft outline-none"
              >
                <DropdownMenuItem
                  v-for="action in refreshActions"
                  :key="action.mode"
                  class="flex cursor-pointer items-center gap-2.5 rounded-lg px-3 py-2 text-sm text-surface-700 outline-none transition-colors duration-150 hover:bg-primary-50 hover:text-primary-700 focus:bg-primary-50"
                  @click="handleRefresh(action.mode)"
                >
                  <component
                    :is="action.icon"
                    class="h-4 w-4 shrink-0 text-primary-500"
                  />
                  {{ action.label }}
                </DropdownMenuItem>
              </DropdownMenuContent>
            </DropdownMenuPortal>
          </DropdownMenuRoot>
        </div>

        <!-- 简介（右栏内） -->
        <p
          v-if="overview"
          class="mt-5 text-sm leading-6 text-surface-600"
        >
          {{ overview }}
        </p>

        <!-- 插槽：简介之下的页面级扩展位。用 v-if 包裹，调用方不传时不产生多余 DOM/间距；间距统一由本容器 mt-5 提供 -->
        <div v-if="$slots.default" class="mt-5">
          <slot />
        </div>
      </div>
    </div>
  </div>
</template>
