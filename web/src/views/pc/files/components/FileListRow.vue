<script setup lang="ts">
/**
 * 文件列表行
 */
import { Check, Download, Globe } from '@lucide/vue'
import { cn } from '@/utils/cn'
import FileRowActions from './FileRowActions.vue'
import type { Component } from 'vue'
import type { FileNodeVo } from '@/types/file'

type DisplayFile = FileNodeVo & {
  iconType: string
  displaySize: string
  displayDate: string
  selected: boolean
}

interface Props {
  file: DisplayFile
  fileIconMap: Record<string, Component>
}

defineProps<Props>()
const emit = defineEmits(['rowClick', 'toggleSelect', 'download', 'rename', 'copy', 'move', 'remove'])
</script>

<template>
  <div
    :class="
      cn(
        'group grid cursor-pointer grid-cols-[48px_1fr_140px_180px_80px] items-center px-5 py-3.5 text-sm transition-all duration-200 ease-out-expo hover:bg-surface-50',
        file.selected && 'bg-primary-50/40 hover:bg-primary-50/60'
      )
    "
    @click="emit('rowClick', file)"
  >
    <!-- 复选框 -->
    <div
      class="flex items-center"
      @click.stop="emit('toggleSelect', file.id)"
    >
      <button
        :class="
          cn(
            'flex h-4 w-4 items-center justify-center rounded border border-surface-300 bg-white transition-colors duration-200 hover:border-primary-400',
            file.selected && 'border-primary-500 bg-primary-500 text-white'
          )
        "
      >
        <Check
          v-if="file.selected"
          class="h-3 w-3"
        />
      </button>
    </div>

    <!-- 文件名 -->
    <div class="flex min-w-0 items-center gap-3">
      <div
        :class="
          cn(
            'flex h-10 w-10 shrink-0 items-center justify-center rounded-xl transition-colors duration-200',
            file.iconType === 'image' && 'bg-purple-100 text-purple-600',
            file.iconType === 'video' && 'bg-rose-100 text-rose-600',
            file.iconType === 'audio' && 'bg-amber-100 text-amber-600',
            file.iconType === 'doc' && 'bg-blue-100 text-blue-600',
            file.iconType === 'folder' && 'bg-emerald-100 text-emerald-600'
          )
        "
      >
        <component
          :is="fileIconMap[file.iconType]"
          class="h-5 w-5"
        />
      </div>
      <span class="line-clamp-1 font-medium text-surface-800">
        {{ file.name }}
      </span>
      <span
        v-if="file.sourceType === 'remote'"
        class="ml-2 inline-flex shrink-0 items-center gap-0.5 rounded-full bg-sky-50 px-1.5 py-0.5 text-[10px] font-medium text-sky-600"
      >
        <Globe class="h-3 w-3" />
        远程
      </span>
    </div>

    <!-- 大小 -->
    <span class="text-surface-500">{{ file.displaySize }}</span>

    <!-- 上传时间 -->
    <span class="text-surface-500">{{ file.displayDate }}</span>

    <!-- 操作按钮 -->
    <div class="flex justify-end gap-1">
      <button
        class="rounded-lg p-1.5 text-surface-400 opacity-0 transition-all duration-200 hover:bg-surface-100 hover:text-surface-700 group-hover:opacity-100"
        @click.stop="emit('download', file.id)"
      >
        <Download class="h-4 w-4" />
      </button>
      <FileRowActions
        :file="file"
        class="opacity-0 group-hover:opacity-100"
        @rename="emit('rename', $event)"
        @copy="emit('copy', $event)"
        @move="emit('move', $event)"
        @delete="emit('remove', $event)"
      />
    </div>
  </div>
</template>
