<script setup lang="ts">
/**
 * 移动端回收站页面
 */
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import {
  ArrowLeft,
  FileText,
  Image as ImageIcon,
  Film,
  Music,
  FolderUp,
  RotateCcw,
  Trash2,
} from '@lucide/vue'
import { cn } from '@/utils/cn'
import {
  fetchTrashPage,
  permanentDeleteTrash,
  restoreFiles,
} from '@/api/file'
import { useConfirmStore } from '@/store/confirm'
import { useNotificationStore } from '@/store/notification'
import type { Component } from 'vue'
import type { OperationResultVo, RecycleRecordVo } from '@/types/file'

const router = useRouter()
const confirmStore = useConfirmStore()
const notificationStore = useNotificationStore()

const records = ref<RecycleRecordVo[]>([])
const loading = ref(false)
const selectedIds = ref<Set<string>>(new Set())

const selectedRecords = computed(() => records.value.filter((r) => selectedIds.value.has(r.id)))

type FileType = 'image' | 'video' | 'audio' | 'doc' | 'folder'

const fileIconMap: Record<FileType, Component> = {
  doc: FileText,
  image: ImageIcon,
  video: Film,
  audio: Music,
  folder: FolderUp,
}

function inferType(record: RecycleRecordVo): FileType {
  if (record.type === 'folder') return 'folder'
  const ext = record.name.split('.').pop()?.toLowerCase() || ''
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

const displayRecords = computed(() =>
  records.value.map((record) => ({
    ...record,
    type: inferType(record),
    displaySize: formatSize(record.totalSize),
    displayDate: formatDate(record.createTime),
    selected: selectedIds.value.has(record.id),
  })),
)

async function loadTrash() {
  loading.value = true
  try {
    const res = await fetchTrashPage(1, 100)
    records.value = res.records
    selectedIds.value.clear()
  } finally {
    loading.value = false
  }
}

onMounted(loadTrash)

function toggleSelect(id: string) {
  if (selectedIds.value.has(id)) {
    selectedIds.value.delete(id)
  } else {
    selectedIds.value.add(id)
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

async function handleRestore() {
  const targets = selectedRecords.value
  if (targets.length === 0) return
  const confirmed = await confirmStore.open({
    title: '恢复文件',
    message: `确定恢复选中的 ${targets.length} 项到原位置吗？`,
    confirmText: '恢复',
  })
  if (!confirmed) return
  const results = await restoreFiles({
    items: targets.map((r) => ({ id: r.id, strategy: 'auto_rename' })),
  })
  showResult('恢复', results)
  await loadTrash()
}

async function handlePermanentDelete() {
  const targets = selectedRecords.value
  if (targets.length === 0) return
  const confirmed = await confirmStore.open({
    title: '永久删除',
    message: `永久删除后无法恢复，确定删除选中的 ${targets.length} 项吗？`,
    confirmText: '删除',
    type: 'danger',
  })
  if (!confirmed) return
  const results = await permanentDeleteTrash({ ids: targets.map((r) => r.id) })
  showResult('删除', results)
  await loadTrash()
}

function showResult(action: string, results: OperationResultVo[]) {
  const success = results.filter((r) => r.status === 'success').length
  const skipped = results.filter((r) => r.status === 'skipped').length
  const failed = results.filter((r) => r.status === 'failed').length
  if (failed > 0) {
    notificationStore.error(`${action}失败 ${failed} 项`)
  } else if (skipped > 0) {
    notificationStore.show(`${action}跳过 ${skipped} 项`, 'info')
  } else {
    notificationStore.success(`${action}成功 ${success} 项`)
  }
}
</script>

<template>
  <div class="flex h-full flex-col bg-surface-50">
    <!-- 顶部工具栏 -->
    <div class="sticky top-0 z-10 border-b border-surface-200 bg-white/90 px-4 py-3 backdrop-blur-md">
      <div class="flex items-center justify-between">
        <div class="flex items-center gap-3">
          <button
            class="rounded-lg p-2 text-surface-600 hover:bg-surface-100"
            @click="router.push('/files')"
          >
            <ArrowLeft class="h-5 w-5" />
          </button>
          <h1 class="text-base font-semibold text-surface-900">
            回收站
          </h1>
        </div>

        <div class="flex items-center gap-2">
          <button
            :class="cn(
              'flex h-9 items-center gap-1 rounded-xl px-3 text-sm font-medium shadow-soft active:scale-95',
              selectedIds.size > 0
                ? 'bg-primary-600 text-white'
                : 'cursor-not-allowed bg-surface-200 text-surface-400'
            )"
            :disabled="selectedIds.size === 0"
            @click="handleRestore"
          >
            <RotateCcw class="h-4 w-4" />
            恢复
          </button>

          <button
            :class="cn(
              'flex h-9 items-center gap-1 rounded-xl px-3 text-sm font-medium shadow-soft active:scale-95',
              selectedIds.size > 0
                ? 'bg-rose-600 text-white'
                : 'cursor-not-allowed bg-surface-200 text-surface-400'
            )"
            :disabled="selectedIds.size === 0"
            @click="handlePermanentDelete"
          >
            <Trash2 class="h-4 w-4" />
            删除
          </button>
        </div>
      </div>
    </div>

    <!-- 记录列表 -->
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
          v-for="record in displayRecords"
          :key="record.id"
          :class="cn(
            'flex items-center gap-3 rounded-2xl border bg-white p-4 shadow-card transition-all active:scale-[0.99]',
            record.selected ? 'border-primary-300 bg-primary-50/40' : 'border-surface-200'
          )"
          @click="toggleSelect(record.id)"
        >
          <div
            :class="cn('flex h-12 w-12 shrink-0 items-center justify-center rounded-xl', getTypeStyle(record.type))"
          >
            <component
              :is="fileIconMap[record.type]"
              class="h-6 w-6"
            />
          </div>

          <div class="min-w-0 flex-1">
            <p class="truncate text-sm font-medium text-surface-900">
              {{ record.name }}
            </p>
            <p class="mt-0.5 text-xs text-surface-500">
              {{ record.displaySize }} · {{ record.displayDate }}
            </p>
          </div>
        </div>
      </div>

      <p
        v-if="!loading && displayRecords.length === 0"
        class="py-10 text-center text-sm text-surface-500"
      >
        回收站为空
      </p>
    </div>
  </div>
</template>
