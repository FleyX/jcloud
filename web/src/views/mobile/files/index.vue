<script setup lang="ts">
/**
 * 移动端文件列表
 * - 真实接口：上传、列表查询、单文件下载、批量移动/复制/删除/下载
 */
import { computed, onMounted, ref } from 'vue'
import {
  FileText,
  Image as ImageIcon,
  Film,
  Music,
  FolderUp,
  Search,
  X,
  Plus,
  Download,
  Check,
  ChevronRight,
  ArrowUp,
  ArrowDown,
} from '@lucide/vue'
import { cn } from '@/utils/cn'
import { deleteToTrash, downloadBatchFiles, downloadFile, fetchFilePage } from '@/api/file'
import { useConfirmStore } from '@/store/confirm'
import { useNotificationStore } from '@/store/notification'
import { useTransferStore } from '@/store/transfer'
import { useBatchUpload } from '@/composables/useBatchUpload'
import type { UploadBatchFile } from '@/composables/useBatchUpload'
import FilePreviewDrawer from '@/components/files/FilePreviewDrawer.vue'
import MoveCopyModal from '@/views/pc/files/components/MoveCopyModal.vue'
import MobileBatchActionBar from './components/MobileBatchActionBar.vue'
import FileConflictModal from '@/components/files/FileConflictModal.vue'
import type { Component } from 'vue'
import type { ConflictItemVo, ConflictStrategy, FileNodeVo, OperationResultVo, FileSortField, FileSortOrder } from '@/types/file'

const confirmStore = useConfirmStore()
const notificationStore = useNotificationStore()
const transferStore = useTransferStore()
const { uploadBatch } = useBatchUpload()

const files = ref<FileNodeVo[]>([])
const keyword = ref('')
const loading = ref(false)
const fileInput = ref<HTMLInputElement | null>(null)
const previewOpen = ref(false)
const previewTarget = ref<FileNodeVo | null>(null)
const selectionMode = ref(false)
const selectedIds = ref<Set<string>>(new Set())
const moveCopyOpen = ref(false)
const moveCopyType = ref<'move' | 'copy'>('move')
const moveCopyTargets = ref<FileNodeVo[]>([])
const currentParentId = ref('0')
const breadcrumbStack = ref<Array<{ id: string; name: string }>>([{ id: '0', name: '全部文件' }])
const sortField = ref<FileSortField>('createTime')
const sortOrder = ref<FileSortOrder>('desc')
const uploadConflictOpen = ref(false)
const uploadConflicts = ref<ConflictItemVo[]>([])
let uploadConflictResolve: ((strategies: Record<string, ConflictStrategy> | null) => void) | null = null

function openPreview(file: FileNodeVo) {
  if (file.type !== 'file') return
  previewTarget.value = file
  previewOpen.value = true
}

function enterFolder(file: FileNodeVo) {
  if (file.type !== 'folder') return
  currentParentId.value = file.id
  breadcrumbStack.value.push({ id: file.id, name: file.name })
  keyword.value = ''
  loadFiles()
}

function navigateToBreadcrumb(index: number) {
  breadcrumbStack.value = breadcrumbStack.value.slice(0, index + 1)
  currentParentId.value = breadcrumbStack.value[index].id
  keyword.value = ''
  loadFiles()
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
  if (file.type === 'folder') return 'folder'
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
  return time
}

const isSearching = computed(() => keyword.value.trim().length > 0)
const folders = computed(() => files.value.filter((file) => file.type === 'folder'))
const selectedFiles = computed(() => files.value.filter((file) => selectedIds.value.has(file.id)))
const isAllSelected = computed(() => files.value.length > 0 && selectedIds.value.size === files.value.length)

const filteredFiles = computed(() =>
  files.value.map((file) => ({
    ...file,
    iconType: inferType(file),
    displaySize: formatSize(file.size),
    displayDate: formatDate(file.createTime),
    selected: selectedIds.value.has(file.id),
  })),
)

async function loadFiles() {
  loading.value = true
  try {
    const res = await fetchFilePage({
      parentId: currentParentId.value,
      name: keyword.value,
      sortField: sortField.value,
      sortOrder: sortOrder.value,
      pageNum: 1,
      pageSize: 100,
    })
    files.value = res.records
    selectedIds.value.clear()
  } finally {
    loading.value = false
  }
}

const sortFieldOptions: { label: string; value: FileSortField }[] = [
  { label: '上传时间', value: 'createTime' },
  { label: '文件名', value: 'name' },
  { label: '大小', value: 'size' },
]

function handleSortFieldChange(event: Event) {
  const value = (event.target as HTMLSelectElement).value as FileSortField
  sortField.value = value
  loadFiles()
}

function toggleSortOrder() {
  sortOrder.value = sortOrder.value === 'asc' ? 'desc' : 'asc'
  loadFiles()
}

onMounted(loadFiles)

function handleSearch() {
  loadFiles()
}

function clearSearch() {
  keyword.value = ''
  loadFiles()
}

function triggerFileSelect() {
  fileInput.value?.click()
}

function openUploadConflict(conflicts: ConflictItemVo[]): Promise<Record<string, ConflictStrategy> | null> {
  return new Promise((resolve) => {
    uploadConflicts.value = conflicts
    uploadConflictResolve = resolve
    uploadConflictOpen.value = true
  })
}

function handleUploadConflictConfirm(strategies: Record<string, ConflictStrategy>) {
  uploadConflictOpen.value = false
  uploadConflictResolve?.(strategies)
  uploadConflictResolve = null
}

function handleUploadConflictCancel() {
  uploadConflictOpen.value = false
  uploadConflictResolve?.(null)
  uploadConflictResolve = null
}

async function handleFileChange(event: Event) {
  const target = event.target as HTMLInputElement
  const files = Array.from(target.files ?? [])
  if (files.length === 0) return
  const batchFiles: UploadBatchFile[] = files.map((file) => ({ file }))
  try {
    await uploadBatch(batchFiles, currentParentId.value, {
      onComplete: loadFiles,
      openConflict: openUploadConflict,
    })
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

function enterSelectionMode() {
  selectionMode.value = true
}

function exitSelectionMode() {
  selectionMode.value = false
  selectedIds.value.clear()
}

function toggleSelect(id: string) {
  if (selectedIds.value.has(id)) {
    selectedIds.value.delete(id)
  } else {
    selectedIds.value.add(id)
  }
}

function handleRowClick(file: FileNodeVo) {
  if (selectionMode.value) {
    toggleSelect(file.id)
    return
  }
  if (file.type === 'folder') {
    enterFolder(file)
  } else {
    openPreview(file)
  }
}

function toggleSelectAll() {
  if (isAllSelected.value) {
    selectedIds.value.clear()
  } else {
    selectedIds.value = new Set(files.value.map((file) => file.id))
  }
}

function openMoveCopy(type: 'move' | 'copy', targets: FileNodeVo[]) {
  moveCopyType.value = type
  moveCopyTargets.value = targets
  moveCopyOpen.value = true
}

async function handleMoveCopyResult(results: OperationResultVo[]) {
  const successCount = results.filter((r) => r.status === 'success').length
  if (successCount > 0) {
    exitSelectionMode()
    await loadFiles()
  }
}

async function handleBatchDelete() {
  const targets = selectedFiles.value
  if (targets.length === 0) return
  const confirmed = await confirmStore.open({
    title: '批量删除',
    message: `确定将选中的 ${targets.length} 项移动到回收站吗？`,
    confirmText: '删除',
    type: 'danger',
  })
  if (!confirmed) return
  await deleteToTrash({ ids: targets.map((f) => f.id) })
  notificationStore.success('已移动到回收站')
  exitSelectionMode()
  await loadFiles()
}

async function handleBatchDownload() {
  const targets = selectedFiles.value
  if (targets.length === 0) return
  const taskId = `dl-${Date.now()}`
  transferStore.addDownloadTask({ taskId, fileName: 'archive.zip' })
  try {
    await downloadBatchFiles(
      targets.map((f) => f.id),
      'archive.zip',
      (progress) => transferStore.updateDownloadProgress(taskId, progress),
    )
    transferStore.completeDownloadTask(taskId)
    notificationStore.success('下载完成')
  } catch (err) {
    const message = err instanceof Error ? err.message : '下载失败'
    transferStore.failDownloadTask(taskId, message)
    notificationStore.error(message)
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
            @keyup.enter="handleSearch"
          >
          <button
            v-if="isSearching"
            class="rounded p-1 text-surface-400 hover:bg-surface-200 hover:text-surface-600"
            @click="clearSearch"
          >
            <X class="h-3.5 w-3.5" />
          </button>
        </div>
        <button
          v-if="!selectionMode"
          class="flex h-10 items-center justify-center rounded-xl bg-surface-100 px-3 text-sm font-medium text-surface-700 active:scale-95"
          @click="enterSelectionMode"
        >
          选择
        </button>
        <button
          v-if="!selectionMode"
          class="flex h-10 w-10 items-center justify-center rounded-xl bg-primary-600 text-white shadow-soft active:scale-95"
          @click="triggerFileSelect"
        >
          <Plus class="h-5 w-5" />
        </button>
        <button
          v-if="selectionMode"
          class="flex h-10 items-center justify-center rounded-xl bg-surface-100 px-3 text-sm font-medium text-surface-700 active:scale-95"
          @click="exitSelectionMode"
        >
          取消
        </button>
        <input
          ref="fileInput"
          type="file"
          multiple
          class="hidden"
          @change="handleFileChange"
        >
      </div>
    </div>

    <!-- 排序栏 -->
    <div class="flex items-center justify-between border-b border-surface-200 bg-white px-4 py-2">
      <span class="text-xs text-surface-500">排序</span>
      <div class="flex items-center gap-2">
        <select
          :value="sortField"
          class="rounded-lg border border-surface-200 bg-surface-50 px-2 py-1 text-xs outline-none"
          @change="handleSortFieldChange"
        >
          <option
            v-for="option in sortFieldOptions"
            :key="option.value"
            :value="option.value"
          >
            {{ option.label }}
          </option>
        </select>
        <button
          class="flex h-7 w-7 items-center justify-center rounded-lg border border-surface-200 bg-surface-50 text-surface-600 active:bg-surface-100"
          @click="toggleSortOrder"
        >
          <ArrowUp
            v-if="sortOrder === 'asc'"
            class="h-3.5 w-3.5"
          />
          <ArrowDown
            v-else
            class="h-3.5 w-3.5"
          />
        </button>
      </div>
    </div>

    <!-- 面包屑导航 -->
    <div
      v-if="breadcrumbStack.length > 1"
      class="flex items-center gap-1 overflow-x-auto border-b border-surface-200 bg-white px-4 py-2 text-sm"
    >
      <template
        v-for="(crumb, index) in breadcrumbStack"
        :key="crumb.id"
      >
        <button
          :class="cn(
            'whitespace-nowrap font-medium',
            index === breadcrumbStack.length - 1 ? 'text-surface-900' : 'text-surface-500'
          )"
          @click="navigateToBreadcrumb(index)"
        >
          {{ crumb.name }}
        </button>
        <ChevronRight
          v-if="index < breadcrumbStack.length - 1"
          class="h-4 w-4 shrink-0 text-surface-300"
        />
      </template>
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
          @click="handleRowClick(file)"
        >
          <div
            v-if="selectionMode"
            class="flex h-6 w-6 shrink-0 items-center justify-center rounded border border-surface-300 transition-colors"
            :class="file.selected ? 'border-primary-500 bg-primary-500 text-white' : 'bg-white'"
            @click.stop="toggleSelect(file.id)"
          >
            <Check
              v-if="file.selected"
              class="h-4 w-4"
            />
          </div>

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
            v-if="!selectionMode && file.type === 'file'"
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
        {{ isSearching ? '未找到相关文件' : '暂无文件，点击右上角上传' }}
      </p>
    </div>

    <MoveCopyModal
      :open="moveCopyOpen"
      :type="moveCopyType"
      :files="moveCopyTargets"
      :folders="folders"
      @close="moveCopyOpen = false"
      @confirm="handleMoveCopyResult"
    />

    <MobileBatchActionBar
      v-if="selectionMode"
      :selected-count="selectedIds.size"
      :total-count="files.length"
      @move="openMoveCopy('move', selectedFiles)"
      @copy="openMoveCopy('copy', selectedFiles)"
      @download="handleBatchDownload"
      @delete="handleBatchDelete"
      @clear="exitSelectionMode"
      @select-all="toggleSelectAll"
    />

    <FilePreviewDrawer
      v-model:open="previewOpen"
      :file="previewTarget"
    />

    <FileConflictModal
      v-model:open="uploadConflictOpen"
      title="上传冲突"
      confirm-text="确认上传"
      :conflicts="uploadConflicts"
      @confirm="handleUploadConflictConfirm"
      @cancel="handleUploadConflictCancel"
    />
  </div>
</template>
