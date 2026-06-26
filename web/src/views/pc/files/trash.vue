<script setup lang="ts">
/**
 * PC 回收站页面
 * - 展示被删除的文件/文件夹
 * - 支持恢复与永久删除
 */
import { computed, onMounted, ref } from 'vue'
import {
  Check,
  FileText,
  FolderUp,
  Image as ImageIcon,
  Film,
  Music,
  RotateCcw,
  Trash2,
} from '@lucide/vue'
import { cn } from '@/utils/cn'
import {
  fetchTrashPage,
  permanentDeleteTrash,
  preCheckRestore,
  restoreFiles,
} from '@/api/file'
import ConflictResolveModal from '@/components/files/ConflictResolveModal.vue'
import { useConfirmStore } from '@/store/confirm'
import { useNotificationStore } from '@/store/notification'
import type { Component } from 'vue'
import type { ConflictItemVo, ConflictStrategy, OperationResultVo, RecycleRecordVo } from '@/types/file'

const confirmStore = useConfirmStore()
const notificationStore = useNotificationStore()

const records = ref<RecycleRecordVo[]>([])
const loading = ref(false)
const selectedIds = ref<Set<string>>(new Set())
const conflictOpen = ref(false)
const conflicts = ref<ConflictItemVo[]>([])
const pendingRestoreRecords = ref<RecycleRecordVo[]>([])

const isAllSelected = computed(() => records.value.length > 0 && selectedIds.value.size === records.value.length)
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
  return time.replace(' ', '\n').split('\n')[0]
}

const displayRecords = computed(() =>
  records.value.map((record) => ({
    ...record,
    iconType: inferType(record),
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

function toggleSelectAll() {
  if (isAllSelected.value) {
    selectedIds.value.clear()
  } else {
    selectedIds.value = new Set(records.value.map((r) => r.id))
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

  const conflictList = await preCheckRestore({ ids: targets.map((r) => r.id) })
  if (conflictList.length > 0) {
    pendingRestoreRecords.value = targets
    conflicts.value = conflictList
    conflictOpen.value = true
    return
  }

  await executeRestore(targets)
}

async function executeRestore(targets: RecycleRecordVo[], strategies?: Record<string, ConflictStrategy>) {
  const results = await restoreFiles({
    items: targets.map((r) => ({
      id: r.id,
      strategy: strategies?.[r.nodeId ?? r.id] ?? 'auto_rename',
    })),
  })
  showResult('恢复', results)
  await loadTrash()
}

function handleConflictConfirm(strategies: Record<string, ConflictStrategy>) {
  executeRestore(pendingRestoreRecords.value, strategies)
}

function handleConflictCancel() {
  pendingRestoreRecords.value = []
  conflicts.value = []
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
  <div class="mx-auto h-full max-w-7xl">
    <!-- 顶部工具栏 -->
    <div class="mb-6 flex items-center justify-between">
      <h1 class="text-lg font-semibold text-surface-900">
        回收站
      </h1>

      <div class="flex items-center gap-3">
        <button
          :class="cn(
            'flex h-9 items-center gap-2 rounded-xl px-4 text-sm font-semibold shadow-soft transition-all active:scale-95',
            selectedIds.size > 0
              ? 'bg-primary-600 text-white hover:bg-primary-700'
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
            'flex h-9 items-center gap-2 rounded-xl px-4 text-sm font-semibold shadow-soft transition-all active:scale-95',
            selectedIds.size > 0
              ? 'bg-rose-600 text-white hover:bg-rose-700'
              : 'cursor-not-allowed bg-surface-200 text-surface-400'
          )"
          :disabled="selectedIds.size === 0"
          @click="handlePermanentDelete"
        >
          <Trash2 class="h-4 w-4" />
          永久删除
        </button>
      </div>
    </div>

    <!-- 回收站列表 -->
    <div class="overflow-hidden rounded-3xl border border-surface-200 bg-white shadow-soft">
      <!-- 表头 -->
      <div
        class="grid grid-cols-[48px_1fr_140px_160px_80px] items-center border-b border-surface-200 bg-surface-50/80 px-5 py-3 text-xs font-semibold uppercase tracking-wider text-surface-500"
      >
        <div>
          <button
            :class="cn(
              'flex h-4 w-4 items-center justify-center rounded border border-surface-300 bg-white transition-colors duration-150 hover:border-primary-400',
              isAllSelected && 'border-primary-500 bg-primary-500 text-white'
            )"
            @click="toggleSelectAll"
          >
            <Check
              v-if="isAllSelected"
              class="h-3 w-3"
            />
          </button>
        </div>
        <span>文件名</span>
        <span>大小</span>
        <span>删除时间</span>
        <span class="text-right">原位置</span>
      </div>

      <!-- 加载中 -->
      <div
        v-if="loading"
        class="px-5 py-12 text-center text-sm text-surface-500"
      >
        加载中...
      </div>

      <!-- 记录行 -->
      <div
        v-else
        class="divide-y divide-surface-100"
      >
        <div
          v-for="record in displayRecords"
          :key="record.id"
          :class="cn(
            'group grid cursor-pointer grid-cols-[48px_1fr_140px_160px_80px] items-center px-5 py-3.5 text-sm transition-all duration-200 ease-out-expo hover:bg-surface-50',
            record.selected && 'bg-primary-50/40 hover:bg-primary-50/60'
          )"
        >
          <div
            class="flex items-center"
            @click.stop="toggleSelect(record.id)"
          >
            <button
              :class="cn(
                'flex h-4 w-4 items-center justify-center rounded border border-surface-300 bg-white transition-colors duration-200 hover:border-primary-400',
                record.selected && 'border-primary-500 bg-primary-500 text-white'
              )"
            >
              <Check
                v-if="record.selected"
                class="h-3 w-3"
              />
            </button>
          </div>

          <div class="flex min-w-0 items-center gap-3">
            <div
              :class="cn(
                'flex h-10 w-10 shrink-0 items-center justify-center rounded-xl transition-colors duration-200',
                record.iconType === 'image' && 'bg-purple-100 text-purple-600',
                record.iconType === 'video' && 'bg-rose-100 text-rose-600',
                record.iconType === 'audio' && 'bg-amber-100 text-amber-600',
                record.iconType === 'doc' && 'bg-blue-100 text-blue-600',
                record.iconType === 'folder' && 'bg-emerald-100 text-emerald-600'
              )"
            >
              <component
                :is="fileIconMap[record.iconType]"
                class="h-5 w-5"
              />
            </div>
            <span class="line-clamp-1 font-medium text-surface-800">
              {{ record.name }}
            </span>
          </div>

          <span class="text-surface-500">{{ record.displaySize }}</span>

          <span class="text-surface-500">{{ record.displayDate }}</span>

          <span class="text-right text-surface-500">{{ record.originalPathName }}</span>
        </div>

        <p
          v-if="displayRecords.length === 0"
          class="px-5 py-12 text-center text-sm text-surface-500"
        >
          回收站为空
        </p>
      </div>
    </div>

    <ConflictResolveModal
      v-model:open="conflictOpen"
      title="恢复冲突"
      :conflicts="conflicts"
      @confirm="handleConflictConfirm"
      @cancel="handleConflictCancel"
    />
  </div>
</template>
