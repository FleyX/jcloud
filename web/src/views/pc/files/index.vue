<script setup lang="ts">
/**
 * PC 文件列表页
 * - 真实接口：上传、列表查询、下载、新建文件夹、重命名、移动、复制
 * - 根目录固定 parentId = 0
 */
import { computed, onMounted, ref } from 'vue'
import {
  FileText,
  FolderUp,
  Image as ImageIcon,
  Film,
  Music,
} from '@lucide/vue'
import { deleteToTrash, downloadBatchFiles, downloadFile, fetchFilePage } from '@/api/file'
import { createShare, updateShare } from '@/api/share'
import { useConfirmStore } from '@/store/confirm'
import { useNotificationStore } from '@/store/notification'
import { useTransferStore } from '@/store/transfer'
import { useFileOperations } from './composables/useFileOperations'
import { useBatchUpload } from '@/composables/useBatchUpload'
import type { UploadBatchFile } from '@/composables/useBatchUpload'
import CreateFolderModal from './components/CreateFolderModal.vue'
import CreateShareModal from './components/CreateShareModal.vue'
import RenameModal from './components/RenameModal.vue'
import MoveCopyModal from './components/MoveCopyModal.vue'
import BatchActionBar from './components/BatchActionBar.vue'
import FileListHeader from './components/FileListHeader.vue'
import FileListRow from './components/FileListRow.vue'
import FileListToolbar from './components/FileListToolbar.vue'
import FilePreviewModal from '@/components/files/FilePreviewModal.vue'
import FileConflictModal from '@/components/files/FileConflictModal.vue'
import { useFileSort } from './composables/useFileSort'
import type { Component } from 'vue'
import type { ConflictItemVo, ConflictStrategy, FileNodeVo, OperationResultVo, FileSortField } from '@/types/file'
import type { ShareCreateRequest, ShareDetailVo, ShareUpdateRequest } from '@/types/share'

const transferStore = useTransferStore()
const notificationStore = useNotificationStore()
const confirmStore = useConfirmStore()
const { uploadBatch } = useBatchUpload()

const files = ref<FileNodeVo[]>([])
const keyword = ref('')
const loading = ref(false)
const selectedIds = ref<Set<string>>(new Set())
const moveCopyOpen = ref(false)
const moveCopyType = ref<'move' | 'copy'>('move')
const moveCopyTargets = ref<FileNodeVo[]>([])
const previewOpen = ref(false)
const previewTarget = ref<FileNodeVo | null>(null)
const shareOpen = ref(false)
const shareEditTarget = ref<ShareDetailVo | undefined>(undefined)
const currentParentId = ref('0')
const breadcrumbStack = ref<Array<{ id: string; name: string }>>([{ id: '0', name: '全部文件' }])
const { sortField, sortOrder, toggleSort } = useFileSort()
const uploadConflictOpen = ref(false)
const uploadConflicts = ref<ConflictItemVo[]>([])
let uploadConflictResolve: ((strategies: Record<string, ConflictStrategy> | null) => void) | null = null

function openPreview(file: FileNodeVo) {
  if (file.type !== 'file') return
  previewTarget.value = file
  previewOpen.value = true
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

function enterFolder(file: FileNodeVo) {
  if (file.type !== 'folder') return
  currentParentId.value = file.id
  breadcrumbStack.value.push({ id: file.id, name: file.name })
  keyword.value = ''
  loadFiles()
}

function handleRowClick(file: FileNodeVo) {
  if (file.type === 'folder') {
    enterFolder(file)
  } else {
    openPreview(file)
  }
}

function navigateToBreadcrumb(index: number) {
  breadcrumbStack.value = breadcrumbStack.value.slice(0, index + 1)
  currentParentId.value = breadcrumbStack.value[index].id
  keyword.value = ''
  loadFiles()
}

const {
  createFolderOpen,
  renameOpen,
  renameTarget,
  openCreateFolder,
  handleCreateFolder,
  openRename,
  handleRename,
} = useFileOperations(loadFiles)

const folders = computed(() => files.value.filter((file) => file.type === 'folder'))
const selectedFiles = computed(() => files.value.filter((file) => selectedIds.value.has(file.id)))
const isAllSelected = computed(() => files.value.length > 0 && selectedIds.value.size === files.value.length)

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

const displayFiles = computed(() =>
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

function onSort(field: FileSortField) {
  toggleSort(field, loadFiles)
}

onMounted(loadFiles)

function handleSearch() {
  loadFiles()
}

function clearSearch() {
  keyword.value = ''
  loadFiles()
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

async function handleFolderChange(event: Event) {
  const target = event.target as HTMLInputElement
  const files = Array.from(target.files ?? [])
  if (files.length === 0) return
  const batchFiles: UploadBatchFile[] = files.map((file) => ({
    file,
    relativePath: file.webkitRelativePath || file.name,
  }))
  try {
    await uploadBatch(batchFiles, currentParentId.value, {
      onComplete: loadFiles,
      defaultConflictStrategy: 'keep',
    })
  } finally {
    target.value = ''
  }
}

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
    selectedIds.value = new Set(files.value.map((file) => file.id))
  }
}

function clearSelection() {
  selectedIds.value.clear()
}

function openMoveCopy(type: 'move' | 'copy', targets: FileNodeVo[]) {
  moveCopyType.value = type
  moveCopyTargets.value = targets
  moveCopyOpen.value = true
}

async function handleMoveCopyResult(results: OperationResultVo[]) {
  const successCount = results.filter((r) => r.status === 'success').length
  if (successCount > 0) {
    await loadFiles()
  }
}

async function handleDelete(file: FileNodeVo) {
  const confirmed = await confirmStore.open({
    title: '删除文件',
    message: `确定将 "${file.name}" 移动到回收站吗？`,
    confirmText: '删除',
    type: 'danger',
  })
  if (!confirmed) return
  await deleteToTrash({ ids: [file.id] })
  notificationStore.success('已移动到回收站')
  await loadFiles()
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

function openShareModal() {
  shareEditTarget.value = undefined
  shareOpen.value = true
}

async function handleCreateShare(payload: ShareCreateRequest) {
  try {
    const share = await createShare(payload)
    shareOpen.value = false
    selectedIds.value.clear()
    notificationStore.success('分享创建成功')
    // TODO: 跳转到我的分享页或展示链接
    console.log('share created', share)
  } catch (err) {
    const message = err instanceof Error ? err.message : '创建分享失败'
    notificationStore.error(message)
  }
}

async function handleUpdateShare(payload: ShareUpdateRequest) {
  if (!shareEditTarget.value) return
  try {
    await updateShare(shareEditTarget.value.id, payload)
    shareOpen.value = false
    notificationStore.success('分享已更新')
  } catch (err) {
    const message = err instanceof Error ? err.message : '更新分享失败'
    notificationStore.error(message)
  }
}

function handleShareConfirm(payload: ShareCreateRequest | ShareUpdateRequest) {
  if (shareEditTarget.value) {
    handleUpdateShare(payload as ShareUpdateRequest)
  } else {
    handleCreateShare(payload as ShareCreateRequest)
  }
}
</script>

<template>
  <div class="mx-auto h-full max-w-7xl">
    <!-- 顶部工具栏 -->
    <FileListToolbar
      v-model:keyword="keyword"
      :breadcrumb-stack="breadcrumbStack"
      :is-searching="isSearching"
      @navigate-to-breadcrumb="navigateToBreadcrumb"
      @search="handleSearch"
      @clear-search="clearSearch"
      @create-folder="openCreateFolder"
      @file-change="handleFileChange"
      @folder-change="handleFolderChange"
    />

    <!-- 文件列表表格 -->
    <div
      class="overflow-hidden rounded-3xl border border-surface-200 bg-white shadow-soft"
    >
      <!-- 表头 -->
      <FileListHeader
        :is-all-selected="isAllSelected"
        :sort-field="sortField"
        :sort-order="sortOrder"
        @toggle-select-all="toggleSelectAll"
        @sort="onSort"
      />

      <!-- 加载中 -->
      <div
        v-if="loading"
        class="px-5 py-12 text-center text-sm text-surface-500"
      >
        加载中...
      </div>

      <!-- 文件行 -->
      <div
        v-else
        class="divide-y divide-surface-100"
      >
        <FileListRow
          v-for="file in displayFiles"
          :key="file.id"
          :file="file"
          :file-icon-map="fileIconMap"
          @row-click="handleRowClick"
          @toggle-select="toggleSelect"
          @download="downloadFile"
          @rename="openRename"
          @copy="(f) => openMoveCopy('copy', [f])"
          @move="(f) => openMoveCopy('move', [f])"
          @remove="handleDelete"
        />

        <p
          v-if="displayFiles.length === 0"
          class="px-5 py-12 text-center text-sm text-surface-500"
        >
          {{ isSearching ? '未找到相关文件' : '暂无文件，点击右上角上传' }}
        </p>
      </div>
    </div>

    <CreateFolderModal
      :open="createFolderOpen"
      :parent-id="currentParentId"
      @close="createFolderOpen = false"
      @confirm="(name: string) => handleCreateFolder(name, currentParentId)"
    />

    <RenameModal
      :open="renameOpen"
      :file="renameTarget"
      @close="renameOpen = false"
      @confirm="handleRename"
    />

    <MoveCopyModal
      :open="moveCopyOpen"
      :type="moveCopyType"
      :files="moveCopyTargets"
      :folders="folders"
      @close="moveCopyOpen = false"
      @confirm="handleMoveCopyResult"
    />

    <BatchActionBar
      :selected-count="selectedIds.size"
      @move="openMoveCopy('move', selectedFiles)"
      @copy="openMoveCopy('copy', selectedFiles)"
      @download="handleBatchDownload"
      @share="openShareModal"
      @delete="handleBatchDelete"
      @clear="clearSelection"
    />

    <CreateShareModal
      :open="shareOpen"
      :item-ids="Array.from(selectedIds)"
      :edit-share="shareEditTarget"
      @close="shareOpen = false"
      @confirm="handleShareConfirm"
    />

    <FilePreviewModal
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
