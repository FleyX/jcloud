<script setup lang="ts">
/**
 * 媒体库卡片菜单（右下角悬浮下拉）
 * - radix-vue DropdownMenu，点击菜单项 emit action 后自动关闭
 * - 电影/剧集库三项：「扫描媒体库」「刷新缺失元数据」「强制刷新所有元数据」；其他库仅「扫描媒体库」
 * - 按钮可见性（PC hover / 移动端常驻）由父级外层控制
 */
import { computed, ref } from 'vue'
import {
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuRoot,
  DropdownMenuTrigger,
} from 'radix-vue'
import { MoreVertical, RefreshCcw, RefreshCw, ScanSearch } from '@lucide/vue'
import type { Component } from 'vue'
import type { MediaType } from '@/types/media'

/** 库操作类型：扫描 / 刷新缺失元数据 / 强制刷新所有元数据 */
export type LibraryMenuAction = 'scan' | 'refresh-missing' | 'refresh-all'

interface Props {
  mediaType: MediaType
}

const props = defineProps<Props>()

const emit = defineEmits<{
  action: [kind: LibraryMenuAction]
}>()

const open = ref(false)

const actions = computed<Array<{ kind: LibraryMenuAction; label: string; icon: Component }>>(() => {
  const list: Array<{ kind: LibraryMenuAction; label: string; icon: Component }> = [
    { kind: 'scan', label: '扫描媒体库', icon: ScanSearch },
  ]
  if (props.mediaType !== 'other') {
    list.push(
      { kind: 'refresh-missing', label: '刷新缺失元数据', icon: RefreshCw },
      { kind: 'refresh-all', label: '强制刷新所有元数据', icon: RefreshCcw },
    )
  }
  return list
})

function handleAction(kind: LibraryMenuAction) {
  open.value = false
  emit('action', kind)
}
</script>

<template>
  <DropdownMenuRoot v-model:open="open">
    <DropdownMenuTrigger as-child>
      <button
        class="flex h-8 w-8 items-center justify-center rounded-full bg-black/50 text-white backdrop-blur-sm transition-colors hover:bg-black/70"
        title="库操作"
        @click.stop
      >
        <MoreVertical class="h-4 w-4" />
      </button>
    </DropdownMenuTrigger>

    <DropdownMenuContent
      align="end"
      :side-offset="6"
      class="z-50 min-w-[150px] overflow-hidden rounded-xl border border-surface-200 bg-white p-1.5 shadow-soft outline-none"
    >
      <DropdownMenuItem
        v-for="action in actions"
        :key="action.kind"
        class="flex cursor-pointer items-center gap-2.5 rounded-lg px-3 py-2 text-sm text-surface-700 outline-none transition-colors duration-150 hover:bg-primary-50 hover:text-primary-700 focus:bg-primary-50"
        @click="handleAction(action.kind)"
      >
        <component
          :is="action.icon"
          class="h-4 w-4 shrink-0 text-primary-500"
        />
        {{ action.label }}
      </DropdownMenuItem>
    </DropdownMenuContent>
  </DropdownMenuRoot>
</template>
