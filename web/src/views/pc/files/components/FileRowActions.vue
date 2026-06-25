<script setup lang="ts">
/**
 * 文件行操作菜单
 */
import {
  DropdownMenuRoot,
  DropdownMenuTrigger,
  DropdownMenuContent,
  DropdownMenuItem,
} from 'radix-vue'
import { MoreVertical, Pencil, Copy, FolderInput, Trash2 } from '@lucide/vue'
import { cn } from '@/utils/cn'

import type { FileNodeVo } from '@/types/file'

interface Props {
  file: FileNodeVo
}

const props = defineProps<Props>()
const emit = defineEmits<{
  rename: [file: FileNodeVo]
  copy: [file: FileNodeVo]
  move: [file: FileNodeVo]
  delete: [file: FileNodeVo]
}>()

function handleRename() {
  emit('rename', props.file)
}

function handleCopy() {
  emit('copy', props.file)
}

function handleMove() {
  emit('move', props.file)
}

function handleDelete() {
  emit('delete', props.file)
}
</script>

<template>
  <DropdownMenuRoot>
    <DropdownMenuTrigger as-child>
      <button
        :class="
          cn(
            'rounded-lg p-1.5 text-surface-400 opacity-0 transition-all duration-200 hover:bg-surface-100 hover:text-surface-700 group-hover:opacity-100'
          )
        "
        @click.stop
      >
        <MoreVertical class="h-4 w-4" />
      </button>
    </DropdownMenuTrigger>

    <DropdownMenuContent
      align="end"
      :side-offset="8"
      class="min-w-[140px] overflow-hidden rounded-2xl border border-surface-200 bg-white p-1.5 shadow-soft outline-none"
    >
      <DropdownMenuItem
        class="flex cursor-pointer items-center gap-2.5 rounded-xl px-3 py-2 text-sm text-surface-700 outline-none transition-colors duration-150 hover:bg-primary-50 hover:text-primary-700 focus:bg-primary-50"
        @click.stop="handleRename"
      >
        <Pencil class="h-4 w-4 text-primary-500" />
        重命名
      </DropdownMenuItem>
      <DropdownMenuItem
        class="flex cursor-pointer items-center gap-2.5 rounded-xl px-3 py-2 text-sm text-surface-700 outline-none transition-colors duration-150 hover:bg-primary-50 hover:text-primary-700 focus:bg-primary-50"
        @click.stop="handleCopy"
      >
        <Copy class="h-4 w-4 text-primary-500" />
        复制
      </DropdownMenuItem>
      <DropdownMenuItem
        class="flex cursor-pointer items-center gap-2.5 rounded-xl px-3 py-2 text-sm text-surface-700 outline-none transition-colors duration-150 hover:bg-primary-50 hover:text-primary-700 focus:bg-primary-50"
        @click.stop="handleMove"
      >
        <FolderInput class="h-4 w-4 text-primary-500" />
        移动
      </DropdownMenuItem>
      <DropdownMenuItem
        class="flex cursor-pointer items-center gap-2.5 rounded-xl px-3 py-2 text-sm text-red-600 outline-none transition-colors duration-150 hover:bg-red-50 focus:bg-red-50"
        @click.stop="handleDelete"
      >
        <Trash2 class="h-4 w-4 text-red-500" />
        删除
      </DropdownMenuItem>
    </DropdownMenuContent>
  </DropdownMenuRoot>
</template>
