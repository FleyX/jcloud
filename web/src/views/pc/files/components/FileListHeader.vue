<script setup lang="ts">
/**
 * 文件列表表头，支持全选与排序
 */
import { ChevronUp, ChevronDown, Check } from '@lucide/vue'
import { cn } from '@/utils/cn'
import type { FileSortField, FileSortOrder } from '@/types/file'

interface Props {
  isAllSelected: boolean
  sortField: FileSortField
  sortOrder: FileSortOrder
}

const props = defineProps<Props>()
const emit = defineEmits<{
  toggleSelectAll: []
  sort: [field: FileSortField]
}>()

function isActive(field: FileSortField, order: FileSortOrder): boolean {
  return props.sortField === field && props.sortOrder === order
}
</script>

<template>
  <div
    class="grid grid-cols-[48px_1fr_140px_180px_80px] items-center border-b border-surface-200 bg-surface-50/80 px-5 py-3 text-xs font-semibold uppercase tracking-wider text-surface-500"
  >
    <div>
      <button
        :class="
          cn(
            'flex h-4 w-4 items-center justify-center rounded border border-surface-300 bg-white transition-colors duration-150 hover:border-primary-400',
            isAllSelected && 'border-primary-500 bg-primary-500 text-white'
          )
        "
        @click="emit('toggleSelectAll')"
      >
        <Check
          v-if="isAllSelected"
          class="h-3 w-3"
        />
      </button>
    </div>
    <button
      class="flex items-center gap-1 text-left"
      @click="emit('sort', 'name')"
    >
      <span>文件名</span>
      <span class="flex flex-col -space-y-1">
        <ChevronUp
          :class="cn('h-3 w-3', isActive('name', 'asc') ? 'text-primary-600' : 'text-surface-300')"
        />
        <ChevronDown
          :class="cn('h-3 w-3', isActive('name', 'desc') ? 'text-primary-600' : 'text-surface-300')"
        />
      </span>
    </button>
    <button
      class="flex items-center gap-1 text-left"
      @click="emit('sort', 'size')"
    >
      <span>大小</span>
      <span class="flex flex-col -space-y-1">
        <ChevronUp
          :class="cn('h-3 w-3', isActive('size', 'asc') ? 'text-primary-600' : 'text-surface-300')"
        />
        <ChevronDown
          :class="cn('h-3 w-3', isActive('size', 'desc') ? 'text-primary-600' : 'text-surface-300')"
        />
      </span>
    </button>
    <button
      class="flex items-center gap-1 text-left"
      @click="emit('sort', 'createTime')"
    >
      <span>上传时间</span>
      <span class="flex flex-col -space-y-1">
        <ChevronUp
          :class="cn('h-3 w-3', isActive('createTime', 'asc') ? 'text-primary-600' : 'text-surface-300')"
        />
        <ChevronDown
          :class="cn('h-3 w-3', isActive('createTime', 'desc') ? 'text-primary-600' : 'text-surface-300')"
        />
      </span>
    </button>
    <span class="text-right">操作</span>
  </div>
</template>
