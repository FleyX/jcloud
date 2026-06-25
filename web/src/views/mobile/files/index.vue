<script setup lang="ts">
/**
 * 移动端文件列表
 * - 真实接口：上传、列表查询、下载
 */
import { computed, onMounted, ref } from 'vue'
import {
  FileText,
  Image as ImageIcon,
  Film,
  Music,
  FolderUp,
  Search,
  Plus,
  Download,
} from '@lucide/vue'
import { cn } from '@/utils/cn'
import { fetchFilePage, uploadFile, downloadFile } from '@/api/file'
import FilePreviewDrawer from '@/components/files/FilePreviewDrawer.vue'
import type { Component } from 'vue'
import type { FileNodeVo } from '@/types/file'

const files = ref<FileNodeVo[]>([])
const keyword = ref('')
const loading = ref(false)
const fileInput = ref<HTMLInputElement | null>(null)
const previewOpen = ref(false)
const previewTarget = ref<FileNodeVo | null>(null)

function openPreview(file: FileNodeVo) {
  if (file.type !== 'file') return
  previewTarget.value = file
  previewOpen.value = true
}

type FileType = 'image' | 'video' | 'audio' | 'doc' | 'folder'

const fileIconMap: Record<FileType, Component> = {
  doc: FileText,
  image: ImageIcon,
  video: Film,
  audio: Music,
  folder: FolderUp,
}

function inferType(file: FileNodeVo): FileType {
  const mime = file.mimeType || ''
  if (mime.startsWith('image/')) return 'image'
  if (mime.startsWith('video/')) return 'video'
  if (mime.startsWith('audio/')) return 'audio'
  const ext = file.name.split('.').pop()?.toLowerCase() || ''
  if (['png', 'jpg', 'jpeg', 'gif', 'webp', 'svg', 'bmp'].includes(ext)) return 'image'
  if (['mp4', 'mov', 'avi', 'mkv', 'webm'].includes(ext)) return 'video'
  if (['mp3', 'wav', 'flac', 'aac', 'ogg'].includes(ext)) return 'audio'
  return 'doc'
}

function formatSize(bytes?: string | number): string {
  const num = Number(bytes)
  if (!num) return '-'
  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  let i = 0
  let size = num
  while (size >= 1024 && i < units.length - 1) {
    size /= 1024
    i++
  }
  return `${size.toFixed(2)} ${units[i]}`
}

function formatDate(time?: string): string {
  if (!time) return '-'
  return time.split(' ')[0]
}

const filteredFiles = computed(() =>
  files.value.map((file) => ({
    ...file,
    iconType: inferType(file),
    displaySize: formatSize(file.size),
    displayDate: formatDate(file.createTime),
  })),
)

async function loadFiles() {
  loading.value = true
  try {
    const res = await fetchFilePage({
      parentId: '0',
      name: keyword.value,
      pageNum: 1,
      pageSize: 100,
    })
    files.value = res.records
  } finally {
    loading.value = false
  }
}

function handleSearch() {
  loadFiles()
}

function triggerFileSelect() {
  fileInput.value?.click()
}

async function handleFileChange(event: Event) {
  const target = event.target as HTMLInputElement
  const file = target.files?.[0]
  if (!file) return
  try {
    await uploadFile(file)
    await loadFiles()
  } finally {
    target.value = ''
  }
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

onMounted(loadFiles)
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
            @keyup.enter="handleSearch"
          >
        </div>
        <button
          class="flex h-10 w-10 items-center justify-center rounded-xl bg-primary-600 text-white shadow-soft active:scale-95"
          @click="triggerFileSelect"
        >
          <Plus class="h-5 w-5" />
        </button>
        <input
          ref="fileInput"
          type="file"
          class="hidden"
          @change="handleFileChange"
        >
      </div>
    </div>

    <!-- 文件列表 -->
    <div class="flex-1 overflow-y-auto p-4">
      <div
        v-if="loading"
        class="py-10 text-center text-sm text-surface-500"
      >
        加载中...
      </div>
      <div
        v-else
        class="space-y-3"
      >
        <div
          v-for="file in filteredFiles"
          :key="file.id"
          class="flex items-center gap-3 rounded-2xl border border-surface-200 bg-white p-4 shadow-card transition-all active:scale-[0.99]"
          @click="openPreview(file)"
        >
          <div
            :class="
              cn(
                'flex h-12 w-12 shrink-0 items-center justify-center rounded-xl',
                getTypeStyle(file.iconType)
              )
            "
          >
            <component
              :is="fileIconMap[file.iconType]"
              class="h-6 w-6"
            />
          </div>

          <div class="min-w-0 flex-1">
            <p class="truncate text-sm font-medium text-surface-900">
              {{ file.name }}
            </p>
            <p class="mt-0.5 text-xs text-surface-500">
              {{ file.displaySize }} · {{ file.displayDate }}
            </p>
          </div>

          <button
            class="rounded-lg p-2 text-surface-400 hover:bg-surface-100 hover:text-surface-700"
            @click.stop="downloadFile(file.id)"
          >
            <Download class="h-5 w-5" />
          </button>
        </div>
      </div>

      <p
        v-if="!loading && filteredFiles.length === 0"
        class="py-10 text-center text-sm text-surface-500"
      >
        暂无文件，点击右上角上传
      </p>
    </div>

    <FilePreviewDrawer
      v-model:open="previewOpen"
      :file="previewTarget"
    />
  </div>
</template>
