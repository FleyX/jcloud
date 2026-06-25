<script setup lang="ts">
/**
 * PC 文件列表页
 * - 真实接口：上传、列表查询、下载、新建文件夹、重命名、移动、复制
 * - 根目录固定 parentId = 0
 */
import { computed, onMounted, ref } from 'vue'
import {
  DropdownMenuRoot,
  DropdownMenuTrigger,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
} from 'radix-vue'
import {
  ChevronRight,
  Upload,
  FileUp,
  FolderUp,
  Plus,
  FolderPlus,
  Download,
  FileText,
  Image as ImageIcon,
  Film,
  Music,
  Search,
  X,
  LayoutGrid,
  List,
  Check,
} from '@lucide/vue'
import { cn } from '@/utils/cn'
import { deleteToTrash, downloadBatchFiles, downloadFile, fetchFilePage, uploadFile } from '@/api/file'
import { useConfirmStore } from '@/store/confirm'
import { useNotificationStore } from '@/store/notification'
import { useTransferStore } from '@/store/transfer'
import { useFileOperations } from './composables/useFileOperations'
import FileRowActions from './components/FileRowActions.vue'
import CreateFolderModal from './components/CreateFolderModal.vue'
import RenameModal from './components/RenameModal.vue'
import MoveCopyModal from './components/MoveCopyModal.vue'
import BatchActionBar from './components/BatchActionBar.vue'
import FilePreviewModal from '@/components/files/FilePreviewModal.vue'
import type { Component } from 'vue'
import type { FileNodeVo, OperationResultVo } from '@/types/file'

const transferStore = useTransferStore()
const notificationStore = useNotificationStore()
const confirmStore = useConfirmStore()

const files = ref<FileNodeVo[]>([])
const keyword = ref('')
const loading = ref(false)
const fileInput = ref<HTMLInputElement | null>(null)
const selectedIds = ref<Set<string>>(new Set())
const moveCopyOpen = ref(false)
const moveCopyType = ref<'move' | 'copy'>('move')
const moveCopyTargets = ref<FileNodeVo[]>([])
const previewOpen = ref(false)
const previewTarget = ref<FileNodeVo | null>(null)

function openPreview(file: FileNodeVo) {
  if (file.type !== 'file') return
  previewTarget.value = file
  previewOpen.value = true
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

const breadcrumbs = ['全部文件']
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
  return time.replace(' ', '\n').split('\n')[0]
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
      parentId: '0',
      name: keyword.value,
      pageNum: 1,
      pageSize: 100,
    })
    files.value = res.records
    selectedIds.value.clear()
  } finally {
    loading.value = false
  }
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

function handleMockUpload() {
  transferStore.addUploadTask({
    fileId: `mock-${Date.now()}`,
    fileName: '示例上传文件.zip',
  })
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
</script>

<template>
  <div class="mx-auto h-full max-w-7xl">
    <!-- 顶部工具栏 -->
    <div class="mb-6 flex items-center justify-between">
      <!-- 面包屑导航 -->
      <nav class="flex items-center gap-1 text-sm">
        <span
          v-for="(crumb, index) in breadcrumbs"
          :key="index"
          class="flex items-center gap-1"
        >
          <span
            :class="
              cn(
                'font-medium',
                index === breadcrumbs.length - 1 ? 'font-semibold text-surface-900' : 'text-surface-500'
              )
            "
          >
            {{ crumb }}
          </span>
          <ChevronRight
            v-if="index < breadcrumbs.length - 1"
            class="h-4 w-4 text-surface-300"
          />
        </span>
      </nav>

      <div class="flex items-center gap-3">
        <!-- 视图切换 -->
        <div class="flex items-center rounded-xl border border-surface-200 bg-white p-1 shadow-card">
          <button class="rounded-lg p-1.5 text-surface-400 hover:bg-surface-100 hover:text-surface-700">
            <LayoutGrid class="h-4 w-4" />
          </button>
          <button class="rounded-lg bg-surface-100 p-1.5 text-surface-700">
            <List class="h-4 w-4" />
          </button>
        </div>

        <!-- 搜索框 -->
        <div
          class="flex h-9 items-center gap-2 rounded-xl border border-surface-200 bg-white px-3 shadow-card transition-shadow duration-200 focus-within:border-primary-300 focus-within:ring-2 focus-within:ring-primary-100"
        >
          <Search class="h-4 w-4 text-surface-400" />
          <input
            v-model="keyword"
            type="text"
            placeholder="搜索文件..."
            class="w-48 bg-transparent text-sm outline-none placeholder:text-surface-400"
            @keyup.enter="handleSearch"
          >
          <button
            v-if="isSearching"
            class="rounded p-0.5 text-surface-400 hover:bg-surface-100 hover:text-surface-600"
            @click="clearSearch"
          >
            <X class="h-3.5 w-3.5" />
          </button>
        </div>

        <button
          class="flex h-9 items-center gap-2 rounded-xl border border-surface-200 bg-white px-3 text-sm font-medium text-surface-700 shadow-card transition-all hover:bg-surface-50 hover:text-surface-900"
          @click="openCreateFolder"
        >
          <FolderPlus class="h-4 w-4 text-primary-500" />
          新建文件夹
        </button>

        <!-- 上传按钮：Radix Vue DropdownMenu -->
        <DropdownMenuRoot>
          <DropdownMenuTrigger as-child>
            <button
              class="flex h-9 items-center gap-2 rounded-xl bg-primary-600 px-4 text-sm font-semibold text-white shadow-soft transition-all duration-200 hover:bg-primary-700 hover:shadow-card active:scale-95"
            >
              <Plus class="h-4 w-4" />
              上传
            </button>
          </DropdownMenuTrigger>

          <DropdownMenuContent
            align="end"
            :side-offset="8"
            class="min-w-[180px] overflow-hidden rounded-2xl border border-surface-200 bg-white p-1.5 shadow-soft outline-none"
          >
            <DropdownMenuItem
              class="flex cursor-pointer items-center gap-2.5 rounded-xl px-3 py-2 text-sm text-surface-700 outline-none transition-colors duration-150 hover:bg-primary-50 hover:text-primary-700 focus:bg-primary-50"
              @click="triggerFileSelect"
            >
              <FileUp class="h-4 w-4 text-primary-500" />
              上传文件
            </DropdownMenuItem>
            <DropdownMenuItem
              class="flex cursor-pointer items-center gap-2.5 rounded-xl px-3 py-2 text-sm text-surface-700 outline-none transition-colors duration-150 hover:bg-primary-50 hover:text-primary-700 focus:bg-primary-50"
              @click="handleMockUpload"
            >
              <FolderUp class="h-4 w-4 text-primary-500" />
              上传文件夹（占位）
            </DropdownMenuItem>
            <DropdownMenuSeparator class="my-1.5 h-px bg-surface-200" />
            <DropdownMenuItem
              class="flex cursor-pointer items-center gap-2.5 rounded-xl px-3 py-2 text-sm text-surface-700 outline-none transition-colors duration-150 hover:bg-primary-50 hover:text-primary-700 focus:bg-primary-50"
            >
              <Upload class="h-4 w-4 text-primary-500" />
              离线下载（占位）
            </DropdownMenuItem>
          </DropdownMenuContent>
        </DropdownMenuRoot>

        <input
          ref="fileInput"
          type="file"
          class="hidden"
          @change="handleFileChange"
        >
      </div>
    </div>

    <!-- 文件列表表格 -->
    <div
      class="overflow-hidden rounded-3xl border border-surface-200 bg-white shadow-soft"
    >
      <!-- 表头 -->
      <div
        class="grid grid-cols-[48px_1fr_140px_160px_80px] items-center border-b border-surface-200 bg-surface-50/80 px-5 py-3 text-xs font-semibold uppercase tracking-wider text-surface-500"
      >
        <div>
          <button
            :class="
              cn(
                'flex h-4 w-4 items-center justify-center rounded border border-surface-300 bg-white transition-colors duration-150 hover:border-primary-400',
                isAllSelected && 'border-primary-500 bg-primary-500 text-white'
              )
            "
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
        <span>上传时间</span>
        <span class="text-right">操作</span>
      </div>

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
        <div
          v-for="file in displayFiles"
          :key="file.id"
          :class="
            cn(
              'group grid cursor-pointer grid-cols-[48px_1fr_140px_160px_80px] items-center px-5 py-3.5 text-sm transition-all duration-200 ease-out-expo hover:bg-surface-50',
              file.selected && 'bg-primary-50/40 hover:bg-primary-50/60'
            )
          "
          @click="openPreview(file)"
        >
          <!-- 复选框 -->
          <div
            class="flex items-center"
            @click.stop="toggleSelect(file.id)"
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
          </div>

          <!-- 大小 -->
          <span class="text-surface-500">{{ file.displaySize }}</span>

          <!-- 上传时间 -->
          <span class="text-surface-500">{{ file.displayDate }}</span>

          <!-- 操作按钮 -->
          <div class="flex justify-end gap-1">
            <button
              class="rounded-lg p-1.5 text-surface-400 opacity-0 transition-all duration-200 hover:bg-surface-100 hover:text-surface-700 group-hover:opacity-100"
              @click.stop="downloadFile(file.id)"
            >
              <Download class="h-4 w-4" />
            </button>
            <FileRowActions
              :file="file"
              class="opacity-0 group-hover:opacity-100"
              @rename="openRename"
              @copy="(f) => openMoveCopy('copy', [f])"
              @move="(f) => openMoveCopy('move', [f])"
              @delete="handleDelete"
            />
          </div>
        </div>

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
      parent-id="0"
      @close="createFolderOpen = false"
      @confirm="handleCreateFolder"
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
      @delete="handleBatchDelete"
      @clear="clearSelection"
    />

    <FilePreviewModal
      v-model:open="previewOpen"
      :file="previewTarget"
    />
  </div>
</template>
