<script setup lang="ts">
/**
 * 播放器控制栏通用选项弹层（倍速/字幕/码率/音轨共用）
 * - radix-vue DropdownMenu，side="top" 向上弹出
 * - 标题 + 可勾选列表，当前选中项打勾高亮
 * - open 通过 v-model 暴露给外层，任一弹层打开时钉住控制栏（不自动隐藏）
 */
import {
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuRoot,
  DropdownMenuTrigger,
} from 'radix-vue'
import { Check } from '@lucide/vue'
import { cn } from '@/utils/cn'

interface MenuOption {
  key: string
  label: string
  checked: boolean
}

interface Props {
  /** 弹层分组标题（如「倍速」「字幕」） */
  title: string
  options: MenuOption[]
}

defineProps<Props>()
const emit = defineEmits<{
  select: [key: string]
}>()

const open = defineModel<boolean>('open', { default: false })

const itemClass = 'flex cursor-pointer items-center gap-2 rounded-lg px-3 py-2 text-sm text-surface-200 outline-none transition-colors hover:bg-white/10 focus:bg-white/10'
</script>

<template>
  <DropdownMenuRoot v-model:open="open">
    <DropdownMenuTrigger as-child>
      <slot />
    </DropdownMenuTrigger>

    <DropdownMenuContent
      side="top"
      align="center"
      :side-offset="8"
      class="z-50 max-h-[70vh] min-w-[160px] overflow-y-auto rounded-xl border border-white/10 bg-surface-950/95 p-1.5 shadow-lg backdrop-blur"
    >
      <p class="px-3 pb-1 pt-2 text-xs font-semibold text-surface-400">
        {{ title }}
      </p>
      <DropdownMenuItem
        v-for="option in options"
        :key="option.key"
        :class="cn(itemClass, option.checked && 'text-primary-300')"
        @click="emit('select', option.key)"
      >
        <Check :class="cn('h-4 w-4 shrink-0', option.checked ? 'opacity-100' : 'opacity-0')" />
        <span class="truncate">{{ option.label }}</span>
      </DropdownMenuItem>
    </DropdownMenuContent>
  </DropdownMenuRoot>
</template>
