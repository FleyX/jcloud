<script setup lang="ts">
/**
 * 批量操作栏
 */
import { FolderInput, Copy, Trash2, Download, X } from '@lucide/vue'
import { cn } from '@/utils/cn'

interface Props {
  selectedCount: number
}

defineProps<Props>()
const emit = defineEmits<{
  move: []
  copy: []
  download: []
  delete: []
  clear: []
}>()
</script>

<template>
  <div
    :class="
      cn(
        'fixed bottom-8 left-1/2 z-40 flex -translate-x-1/2 items-center gap-2 rounded-2xl border border-surface-200 bg-white px-4 py-2.5 shadow-soft transition-all duration-300',
        selectedCount > 0 ? 'translate-y-0 opacity-100' : 'translate-y-20 opacity-0'
      )
    "
  >
    <span class="mr-2 text-sm font-medium text-surface-700">
      已选择 {{ selectedCount }} 项
    </span>
    <button
      class="flex items-center gap-1.5 rounded-xl px-3 py-1.5 text-sm font-medium text-surface-600 transition-colors hover:bg-surface-100"
      @click="emit('move')"
    >
      <FolderInput class="h-4 w-4" />
      移动
    </button>
    <button
      class="flex items-center gap-1.5 rounded-xl px-3 py-1.5 text-sm font-medium text-surface-600 transition-colors hover:bg-surface-100"
      @click="emit('copy')"
    >
      <Copy class="h-4 w-4" />
      复制
    </button>
    <button
      class="flex items-center gap-1.5 rounded-xl px-3 py-1.5 text-sm font-medium text-surface-600 transition-colors hover:bg-surface-100"
      @click="emit('download')"
    >
      <Download class="h-4 w-4" />
      下载
    </button>
    <button
      class="flex items-center gap-1.5 rounded-xl px-3 py-1.5 text-sm font-medium text-red-600 transition-colors hover:bg-red-50"
      @click="emit('delete')"
    >
      <Trash2 class="h-4 w-4" />
      删除
    </button>
    <div class="mx-1 h-4 w-px bg-surface-200" />
    <button
      class="rounded-xl p-1.5 text-surface-400 transition-colors hover:bg-surface-100"
      @click="emit('clear')"
    >
      <X class="h-4 w-4" />
    </button>
  </div>
</template>
