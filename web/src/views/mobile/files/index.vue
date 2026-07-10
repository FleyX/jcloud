<script setup lang="ts">
/**
 * 移动端文件列表页
 * - 视图层保留移动端卡片、选择模式等交互
 * - 列表状态与业务逻辑由 useFileList 提供
 */
import { onMounted, reactive, ref } from 'vue'
import {
  Search,
  X,
  Plus,
  Download,
  Check,
  ChevronRight,
  ArrowUp,
  ArrowDown,
  Globe,
} from '@lucide/vue'
import { cn } from '@/utils/cn'
import { downloadFile } from '@/api/file'
import { fileIconMap, formatDate, getTypeStyle } from '@/utils/fileDisplay'
import { useFileList } from '@/views/files/composables/useFileList'
import CreateShareModal from '@/views/files/components/CreateShareModal.vue'
import MoveCopyModal from '@/views/files/components/MoveCopyModal.vue'
import MobileBatchActionBar from './components/MobileBatchActionBar.vue'
import FilePreviewDrawer from '@/components/files/FilePreviewDrawer.vue'
import FileConflictModal from '@/components/files/FileConflictModal.vue'
import type { FileNodeVo, FileSortField, OperationResultVo } from '@/types/file'
import type { ShareCreateRequest } from '@/types/share'

const list = reactive(useFileList())

const fileInput = ref<HTMLInputElement | null>(null)
const selectionMode = ref(false)

const sortFieldOptions: { label: string; value: FileSortField }[] = [
  { label: '上传时间', value: 'createTime' },
  { label: '文件名', value: 'name' },
  { label: '大小', value: 'size' },
]

function enterSelectionMode() {
  selectionMode.value = true
}

function exitSelectionMode() {
  selectionMode.value = false
  list.clearSelection()
}

function handleRowClick(file: FileNodeVo) {
  if (selectionMode.value) {
    list.toggleSelect(file.id)
    return
  }
  if (file.type === 'folder') {
    list.enterFolder(file)
  } else {
    list.openPreview(file)
  }
}

function triggerFileSelect() {
  fileInput.value?.click()
}

async function handleFileChange(event: Event) {
  const target = event.target as HTMLInputElement
  const files = Array.from(target.files ?? [])
  try {
    await list.uploadFiles(files)
  } finally {
    target.value = ''
  }
}

function handleSortFieldChange(event: Event) {
  const value = (event.target as HTMLSelectElement).value as FileSortField
  list.setSortField(value)
}

async function handleBatchDelete() {
  await list.handleBatchDelete()
  exitSelectionMode()
}

async function handleMoveCopyResult(results: OperationResultVo[]) {
  await list.handleMoveCopyResult(results)
  exitSelectionMode()
}

async function handleCreateShare(payload: ShareCreateRequest) {
  await list.handleCreateShare(payload)
  exitSelectionMode()
}

onMounted(list.loadFiles)
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
            v-model="list.keyword"
            type="text"
            placeholder="搜索文件..."
            class="flex-1 bg-transparent text-sm outline-none placeholder:text-surface-400"
            @keyup.enter="list.handleSearch"
          >
          <button
            v-if="list.isSearching"
            class="rounded p-1 text-surface-400 hover:bg-surface-200 hover:text-surface-600"
            @click="list.clearSearch"
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
          :value="list.sortField"
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
          @click="list.toggleSortOrder"
        >
          <ArrowUp
            v-if="list.sortOrder === 'asc'"
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
      v-if="list.breadcrumbStack.length > 1"
      class="flex items-center gap-1 overflow-x-auto border-b border-surface-200 bg-white px-4 py-2 text-sm"
    >
      <template
        v-for="(crumb, index) in list.breadcrumbStack"
        :key="crumb.id"
      >
        <button
          :class="cn(
            'whitespace-nowrap font-medium',
            index === list.breadcrumbStack.length - 1 ? 'text-surface-900' : 'text-surface-500'
          )"
          @click="list.navigateToBreadcrumb(index)"
        >
          {{ crumb.name }}
        </button>
        <ChevronRight
          v-if="index < list.breadcrumbStack.length - 1"
          class="h-4 w-4 shrink-0 text-surface-300"
        />
      </template>
    </div>

    <!-- 文件列表 -->
    <div class="flex-1 overflow-y-auto p-4">
      <div
        v-if="list.loading"
        class="py-10 text-center text-sm text-surface-500"
      >
        加载中...
      </div>
      <div
        v-else
        class="space-y-3"
      >
        <div
          v-for="file in list.displayFiles"
          :key="file.id"
          class="flex items-center gap-3 rounded-2xl border border-surface-200 bg-white p-4 shadow-card transition-all active:scale-[0.99]"
          @click="handleRowClick(file)"
        >
          <div
            v-if="selectionMode"
            :class="cn(
              'flex h-6 w-6 shrink-0 items-center justify-center rounded border border-surface-300 transition-colors',
              file.selected ? 'border-primary-500 bg-primary-500 text-white' : 'bg-white'
            )"
            @click.stop="list.toggleSelect(file.id)"
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
            <p class="flex items-center gap-1 truncate text-sm font-medium text-surface-900">
              <Globe
                v-if="file.sourceType === 'remote'"
                class="h-4 w-4 shrink-0 text-sky-600"
                title="远程"
              />
              {{ file.name }}
            </p>
            <p class="mt-0.5 text-xs text-surface-500">
              {{ file.displaySize }} · {{ formatDate(file.createTime) }}
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
        v-if="!list.loading && list.displayFiles.length === 0"
        class="py-10 text-center text-sm text-surface-500"
      >
        {{ list.isSearching ? '未找到相关文件' : '暂无文件，点击右上角上传' }}
      </p>
    </div>

    <MoveCopyModal
      :open="list.moveCopyOpen"
      :type="list.moveCopyType"
      :files="list.moveCopyTargets"
      :folders="list.folders"
      @close="list.moveCopyOpen = false"
      @confirm="handleMoveCopyResult"
    />

    <MobileBatchActionBar
      v-if="selectionMode"
      :selected-count="list.selectedIds.size"
      :total-count="list.files.length"
      :has-mixed-source="list.hasMixedSelection"
      @move="list.openMoveCopy('move', list.selectedFiles)"
      @copy="list.openMoveCopy('copy', list.selectedFiles)"
      @download="list.handleBatchDownload"
      @share="list.openShareModal"
      @delete="handleBatchDelete"
      @clear="exitSelectionMode"
      @select-all="list.toggleSelectAll"
    />

    <CreateShareModal
      :open="list.shareOpen"
      :item-ids="Array.from(list.selectedIds)"
      @close="list.shareOpen = false"
      @confirm="handleCreateShare"
    />

    <FilePreviewDrawer
      v-model:open="list.previewOpen"
      :file="list.previewTarget"
    />

    <FileConflictModal
      v-model:open="list.uploadConflictOpen"
      title="上传冲突"
      confirm-text="确认上传"
      :conflicts="list.uploadConflicts"
      @confirm="list.handleUploadConflictConfirm"
      @cancel="list.handleUploadConflictCancel"
    />
  </div>
</template>
