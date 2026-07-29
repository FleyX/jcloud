<script setup lang="ts">
/**
 * 视频目录管理面板（PC/移动端共用）
 */
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { Plus, RefreshCw, Pencil, Trash2, FolderOpen } from '@lucide/vue'
import type { MediaDirectorySaveDto, MediaDirectoryVo, MediaType } from '@/types/media'
import {
  createMediaDirectory,
  deleteMediaDirectory,
  fetchMediaDirectories,
  scanMediaDirectory,
  updateMediaDirectory,
} from '@/api/media'
import DirectoryFormModal from './DirectoryFormModal.vue'
import { useConfirmStore } from '@/store/confirm'

const confirmStore = useConfirmStore()

const directories = ref<MediaDirectoryVo[]>([])
const loading = ref(true)
const formOpen = ref(false)
const editingDirectory = ref<MediaDirectoryVo | null>(null)
const scanningIds = ref<Set<string>>(new Set())

let pollTimer: ReturnType<typeof setTimeout> | null = null

onMounted(load)

onBeforeUnmount(() => {
  if (pollTimer) clearTimeout(pollTimer)
})

async function load() {
  if (pollTimer) {
    clearTimeout(pollTimer)
    pollTimer = null
  }
  loading.value = true
  try {
    directories.value = await fetchMediaDirectories()
  } finally {
    loading.value = false
  }
  // 存在扫描中的目录时轮询刷新状态
  if (directories.value.some((d) => d.lastScanStatus === 'SCANNING')) {
    pollTimer = setTimeout(load, 3000)
  }
}

function isScanning(directory: MediaDirectoryVo): boolean {
  return directory.lastScanStatus === 'SCANNING' || scanningIds.value.has(directory.id)
}

function handleAdd() {
  editingDirectory.value = null
  formOpen.value = true
}

function handleEdit(directory: MediaDirectoryVo) {
  editingDirectory.value = directory
  formOpen.value = true
}

async function handleConfirm(dto: MediaDirectorySaveDto) {
  if (editingDirectory.value) {
    await updateMediaDirectory(editingDirectory.value.id, dto)
  } else {
    await createMediaDirectory(dto)
  }
  formOpen.value = false
  await load()
}

async function handleDelete(directory: MediaDirectoryVo) {
  const confirmed = await confirmStore.open({
    title: '删除视频目录',
    message: `删除「${directory.name}」后其媒体条目将被清空，但不会删除文件。`,
    type: 'danger',
  })
  if (!confirmed) return
  await deleteMediaDirectory(directory.id)
  await load()
}

async function handleScan(directory: MediaDirectoryVo) {
  scanningIds.value.add(directory.id)
  try {
    await scanMediaDirectory(directory.id)
    setTimeout(load, 3000)
  } finally {
    scanningIds.value.delete(directory.id)
  }
}

function typeLabel(type: MediaType): string {
  return { movie: '电影', tv: '电视', other: '其他' }[type]
}

function scanStatusLabel(directory: MediaDirectoryVo): string {
  if (!directory.lastScanStatus) return '未扫描'
  return { SCANNING: '扫描中', COMPLETED: '扫描完成', FAILED: '扫描失败', PARTIAL: '部分失败' }[
    directory.lastScanStatus
  ] ?? directory.lastScanStatus
}

function formatScanTime(time: string | null): string {
  if (!time) return ''
  const date = new Date(time)
  if (Number.isNaN(date.getTime())) return time
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`
}
</script>

<template>
  <div class="p-4 md:p-6">
    <div class="mb-4 flex items-center justify-between">
      <p class="text-sm text-surface-500">
        共 {{ directories.length }} 个目录
      </p>
      <button
        class="flex items-center gap-1.5 rounded-xl bg-primary-600 px-4 py-2 text-sm font-semibold text-white hover:bg-primary-700"
        @click="handleAdd"
      >
        <Plus class="h-4 w-4" />
        新增目录
      </button>
    </div>

    <p
      v-if="loading"
      class="py-16 text-center text-sm text-surface-400"
    >
      加载中…
    </p>
    <p
      v-else-if="directories.length === 0"
      class="py-16 text-center text-sm text-surface-400"
    >
      尚未添加视频目录
    </p>

    <div
      v-else
      class="grid gap-3 lg:grid-cols-2"
    >
      <div
        v-for="directory in directories"
        :key="directory.id"
        class="flex items-center gap-3 rounded-2xl border border-surface-100 bg-white p-4 shadow-soft"
      >
        <div class="flex h-10 w-10 shrink-0 items-center justify-center rounded-xl bg-primary-50 text-primary-600">
          <FolderOpen class="h-5 w-5" />
        </div>
        <div class="min-w-0 flex-1">
          <p class="truncate text-sm font-medium text-surface-800">
            {{ directory.name }}
            <span class="ml-1 rounded-md bg-surface-100 px-1.5 py-0.5 text-xs text-surface-500">
              {{ typeLabel(directory.mediaType) }}
            </span>
          </p>
          <p class="mt-0.5 truncate text-xs text-surface-400">
            {{ directory.itemCount }} 个条目 ·
            <span
              v-if="directory.lastScanStatus === 'SCANNING'"
              class="text-primary-500"
            >扫描中…</span>
            <template v-else>{{ scanStatusLabel(directory) }}</template>
            <span v-if="directory.lastScanTime">· {{ formatScanTime(directory.lastScanTime) }}</span>
            <span v-if="directory.scanCron">· 定时：{{ directory.scanCron }}</span>
          </p>
          <p
            v-if="directory.lastScanError"
            class="mt-0.5 truncate text-xs text-red-400"
          >
            {{ directory.lastScanError }}
          </p>
        </div>
        <div class="flex shrink-0 items-center gap-1">
          <button
            class="rounded-lg p-2 text-surface-400 hover:bg-surface-100 hover:text-primary-600 disabled:cursor-not-allowed disabled:opacity-50"
            title="刷新扫描"
            :disabled="isScanning(directory)"
            @click="handleScan(directory)"
          >
            <RefreshCw :class="['h-4 w-4', isScanning(directory) && 'animate-spin']" />
          </button>
          <button
            class="rounded-lg p-2 text-surface-400 hover:bg-surface-100 hover:text-primary-600"
            title="编辑"
            @click="handleEdit(directory)"
          >
            <Pencil class="h-4 w-4" />
          </button>
          <button
            class="rounded-lg p-2 text-surface-400 hover:bg-red-50 hover:text-red-500"
            title="删除"
            @click="handleDelete(directory)"
          >
            <Trash2 class="h-4 w-4" />
          </button>
        </div>
      </div>
    </div>

    <DirectoryFormModal
      :open="formOpen"
      :editing="editingDirectory"
      @close="formOpen = false"
      @confirm="handleConfirm"
    />
  </div>
</template>
