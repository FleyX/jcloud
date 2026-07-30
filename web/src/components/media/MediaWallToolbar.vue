<script setup lang="ts">
/**
 * 媒体海报墙工具栏：搜索入口 + 排序切换
 */
import { Search, ArrowUp, ArrowDown } from '@lucide/vue'
import type { MediaWallSortField } from './useMediaWall'
import { cn } from '@/utils/cn'

interface Props {
  sortField: MediaWallSortField
  sortOrder: 'asc' | 'desc'
}

defineProps<Props>()
const emit = defineEmits<{
  openSearch: []
  sort: [field: MediaWallSortField]
}>()
</script>

<template>
  <div class="mb-4 flex items-center gap-2">
    <button
      class="rounded-lg p-2 text-surface-400 transition-colors hover:bg-surface-100 hover:text-primary-600"
      title="搜索"
      @click="emit('openSearch')"
    >
      <Search class="h-4 w-4" />
    </button>

    <div class="ml-auto flex items-center gap-1">
      <button
        :class="
          cn(
            'flex items-center gap-1 rounded-lg px-3 py-1.5 text-xs transition-colors',
            sortField === 'added' ? 'bg-surface-200 font-medium text-surface-800' : 'text-surface-400 hover:bg-surface-100'
          )
        "
        @click="emit('sort', 'added')"
      >
        添加时间
        <template v-if="sortField === 'added'">
          <ArrowDown
            v-if="sortOrder === 'desc'"
            class="h-3 w-3"
          />
          <ArrowUp
            v-else
            class="h-3 w-3"
          />
        </template>
      </button>
      <button
        :class="
          cn(
            'flex items-center gap-1 rounded-lg px-3 py-1.5 text-xs transition-colors',
            sortField === 'release' ? 'bg-surface-200 font-medium text-surface-800' : 'text-surface-400 hover:bg-surface-100'
          )
        "
        @click="emit('sort', 'release')"
      >
        发行时间
        <template v-if="sortField === 'release'">
          <ArrowDown
            v-if="sortOrder === 'desc'"
            class="h-3 w-3"
          />
          <ArrowUp
            v-else
            class="h-3 w-3"
          />
        </template>
      </button>
    </div>
  </div>
</template>
