<script setup lang="ts">
/**
 * 移动端文件列表
 * - 卡片式列表，支持搜索与模拟上传
 * - 后续接入真实 API 后替换 mock 数据
 */
import { computed, ref } from 'vue'
import {
  FileText,
  Image as ImageIcon,
  Film,
  Music,
  FolderUp,
  Search,
  Plus,
  MoreVertical,
} from '@lucide/vue'
import { cn } from '@/utils/cn'
import { useTransferStore } from '@/store/transfer'
import type { Component } from 'vue'

const transferStore = useTransferStore()

type FileType = 'image' | 'video' | 'audio' | 'doc' | 'folder'

interface FileItem {
  id: string
  name: string
  type: FileType
  size: string
  updatedAt: string
  selected?: boolean
}

const files = ref<FileItem[]>([
  { id: '1', name: '2024-Q4-产品规划.pdf', type: 'doc', size: '2.4 MB', updatedAt: '2024-11-20' },
  { id: '2', name: '首页 Banner 设计稿.png', type: 'image', size: '8.7 MB', updatedAt: '2024-11-18', selected: true },
  { id: '3', name: '产品发布会开场视频.mp4', type: 'video', size: '156 MB', updatedAt: '2024-11-15' },
  { id: '4', name: '背景音乐精选.mp3', type: 'audio', size: '12.1 MB', updatedAt: '2024-11-12' },
])

const keyword = ref('')

const fileIconMap: Record<FileType, Component> = {
  doc: FileText,
  image: ImageIcon,
  video: Film,
  audio: Music,
  folder: FolderUp,
}

const filteredFiles = computed(() => {
  if (!keyword.value) return files.value
  const lower = keyword.value.trim().toLowerCase()
  return files.value.filter((file) => file.name.toLowerCase().includes(lower))
})

function toggleSelect(file: FileItem) {
  file.selected = !file.selected
}

function handleMockUpload() {
  transferStore.addUploadTask({
    fileId: `mock-${Date.now()}`,
    fileName: '示例上传文件.zip',
  })
}

function getTypeStyle(type: FileType) {
  switch (type) {
    case 'image':
      return 'bg-purple-100 text-purple-600'
    case 'video':
      return 'bg-rose-100 text-rose-600'
    case 'audio':
      return 'bg-amber-100 text-amber-600'
    case 'folder':
      return 'bg-emerald-100 text-emerald-600'
    default:
      return 'bg-blue-100 text-blue-600'
  }
}
</script>

<template>
  <div class="flex h-full flex-col bg-surface-50">
    <!-- 顶部搜索栏 -->
    <div class="sticky top-0 z-10 border-b border-surface-200 bg-white/90 px-4 py-3 backdrop-blur-md">
      <div class="flex items-center gap-3">
        <div
          class="flex flex-1 items-center gap-2 rounded-xl border border-surface-200 bg-surface-50 px-3 py-2.5 transition-all focus-within:border-primary-300 focus-within:ring-2 focus-within:ring-primary-100"
        >
          <Search class="h-4 w-4 text-surface-400" />
          <input
            v-model="keyword"
            type="text"
            placeholder="搜索文件..."
            class="flex-1 bg-transparent text-sm outline-none placeholder:text-surface-400"
          >
        </div>
        <button
          class="flex h-10 w-10 items-center justify-center rounded-xl bg-primary-600 text-white shadow-soft active:scale-95"
          @click="handleMockUpload"
        >
          <Plus class="h-5 w-5" />
        </button>
      </div>
    </div>

    <!-- 文件列表 -->
    <div class="flex-1 overflow-y-auto p-4">
      <div class="space-y-3">
        <div
          v-for="file in filteredFiles"
          :key="file.id"
          :class="
            cn(
              'flex items-center gap-3 rounded-2xl border bg-white p-4 shadow-card transition-all active:scale-[0.99]',
              file.selected
                ? 'border-primary-300 bg-primary-50/60'
                : 'border-surface-200'
            )
          "
          @click="toggleSelect(file)"
        >
          <div
            :class="
              cn(
                'flex h-12 w-12 shrink-0 items-center justify-center rounded-xl',
                getTypeStyle(file.type)
              )
            "
          >
            <component
              :is="fileIconMap[file.type]"
              class="h-6 w-6"
            />
          </div>

          <div class="min-w-0 flex-1">
            <p :class="cn('truncate text-sm font-medium', file.selected ? 'text-primary-700' : 'text-surface-900')">
              {{ file.name }}
            </p>
            <p class="mt-0.5 text-xs text-surface-500">
              {{ file.size }} · {{ file.updatedAt }}
            </p>
          </div>

          <button
            class="rounded-lg p-2 text-surface-400 hover:bg-surface-100 hover:text-surface-700"
            @click.stop
          >
            <MoreVertical class="h-5 w-5" />
          </button>
        </div>
      </div>

      <p
        v-if="filteredFiles.length === 0"
        class="py-10 text-center text-sm text-surface-500"
      >
        未找到匹配的文件
      </p>
    </div>
  </div>
</template>
