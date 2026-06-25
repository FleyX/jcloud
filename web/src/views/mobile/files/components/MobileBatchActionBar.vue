<script setup lang="ts">
/**
 * 移动端底部批量操作栏
 */
import { FolderInput, Copy, Download, Trash2, X, Check } from '@lucide/vue'
import { cn } from '@/utils/cn'
import { computed } from 'vue'

interface Props {
  selectedCount: number
  totalCount: number
}

const props = defineProps<Props>()
const emit = defineEmits<{
  move: []
  copy: []
  download: []
  delete: []
  clear: []
  selectAll: []
}>()

const isAllSelected = computed(() => props.selectedCount > 0 && props.selectedCount === props.totalCount)
</script>

<template>
  <div
    class="fixed bottom-0 left-0 right-0 z-40 border-t border-surface-200 bg-white px-4 pb-safe pt-2 shadow-soft"
  >
    <div class="flex items-center justify-between py-2">
      <div class="flex items-center gap-2">
        <button
          :class="
            cn(
              'flex h-6 w-6 items-center justify-center rounded border border-surface-300 transition-colors',
              isAllSelected ? 'border-primary-500 bg-primary-500 text-white' : 'bg-white'
            )
          "
          @click="emit('selectAll')"
        >
          <Check
            v-if="isAllSelected"
            class="h-4 w-4"
          />
        </button>
        <span class="text-sm font-medium text-surface-700">
          已选 {{ selectedCount }} 项
        </span>
      </div>
      <button
        class="rounded-lg p-2 text-surface-400 hover:bg-surface-100"
        @click="emit('clear')"
      >
        <X class="h-5 w-5" />
      </button>
    </div>

    <div class="grid grid-cols-4 gap-2 pb-3">
      <button
        class="flex flex-col items-center gap-1 rounded-xl py-2 text-xs font-medium text-surface-600 transition-colors hover:bg-surface-100"
        @click="emit('move')"
      >
        <FolderInput class="h-5 w-5" />
        移动
      </button>
      <button
        class="flex flex-col items-center gap-1 rounded-xl py-2 text-xs font-medium text-surface-600 transition-colors hover:bg-surface-100"
        @click="emit('copy')"
      >
        <Copy class="h-5 w-5" />
        复制
      </button>
      <button
        class="flex flex-col items-center gap-1 rounded-xl py-2 text-xs font-medium text-surface-600 transition-colors hover:bg-surface-100"
        @click="emit('download')"
      >
        <Download class="h-5 w-5" />
        下载
      </button>
      <button
        class="flex flex-col items-center gap-1 rounded-xl py-2 text-xs font-medium text-red-600 transition-colors hover:bg-red-50"
        @click="emit('delete')"
      >
        <Trash2 class="h-5 w-5" />
        删除
      </button>
    </div>
  </div>
</template>
