<script setup lang="ts">
/**
 * PC 文件列表页
 * - 视图层仅保留 PC 专用模板与事件桥接
 * - 列表状态与业务逻辑由 useFileList 提供
 */
import { onMounted, reactive } from 'vue'
import { downloadFile } from '@/api/file'
import { fileIconMap } from '@/utils/fileDisplay'
import { useFileList } from '@/views/files/composables/useFileList'
import { useFileOperations } from '@/views/files/composables/useFileOperations'
import CreateFolderModal from '@/views/files/components/CreateFolderModal.vue'
import CreateShareModal from '@/views/files/components/CreateShareModal.vue'
import RenameModal from '@/views/files/components/RenameModal.vue'
import MoveCopyModal from '@/views/files/components/MoveCopyModal.vue'
import BatchActionBar from './components/BatchActionBar.vue'
import FileListHeader from './components/FileListHeader.vue'
import FileListRow from './components/FileListRow.vue'
import FileListToolbar from './components/FileListToolbar.vue'
import FilePreviewModal from '@/components/files/FilePreviewModal.vue'
import FileConflictModal from '@/components/files/FileConflictModal.vue'
import type { FileNodeVo } from '@/types/file'

const list = reactive(useFileList())
const {
  createFolderOpen,
  renameOpen,
  renameTarget,
  openCreateFolder,
  handleCreateFolder,
  openRename,
  handleRename,
} = useFileOperations(list.loadFiles)

function handleRowClick(file: FileNodeVo) {
  if (file.type === 'folder') {
    list.enterFolder(file)
  } else {
    list.openPreview(file)
  }
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

async function handleFolderChange(event: Event) {
  const target = event.target as HTMLInputElement
  const files = Array.from(target.files ?? [])
  try {
    await list.uploadFolder(files)
  } finally {
    target.value = ''
  }
}

onMounted(list.loadFiles)
</script>

<template>
  <div class="mx-auto h-full max-w-7xl">
    <FileListToolbar
      v-model:keyword="list.keyword"
      :breadcrumb-stack="list.breadcrumbStack"
      :is-searching="list.isSearching"
      @navigate-to-breadcrumb="list.navigateToBreadcrumb"
      @search="list.handleSearch"
      @clear-search="list.clearSearch"
      @create-folder="openCreateFolder"
      @file-change="handleFileChange"
      @folder-change="handleFolderChange"
    />

    <div
      class="overflow-hidden rounded-3xl border border-surface-200 bg-white shadow-soft"
    >
      <FileListHeader
        :is-all-selected="list.isAllSelected"
        :sort-field="list.sortField"
        :sort-order="list.sortOrder"
        @toggle-select-all="list.toggleSelectAll"
        @sort="list.toggleSort"
      />

      <div
        v-if="list.loading"
        class="px-5 py-12 text-center text-sm text-surface-500"
      >
        加载中...
      </div>

      <div
        v-else
        class="divide-y divide-surface-100"
      >
        <FileListRow
          v-for="file in list.displayFiles"
          :key="file.id"
          :file="file"
          :file-icon-map="fileIconMap"
          @row-click="handleRowClick"
          @toggle-select="list.toggleSelect"
          @download="downloadFile"
          @rename="openRename"
          @copy="(f: FileNodeVo) => list.openMoveCopy('copy', [f])"
          @move="(f: FileNodeVo) => list.openMoveCopy('move', [f])"
          @remove="list.handleDelete"
        />

        <p
          v-if="list.displayFiles.length === 0"
          class="px-5 py-12 text-center text-sm text-surface-500"
        >
          {{ list.isSearching ? '未找到相关文件' : '暂无文件，点击右上角上传' }}
        </p>
      </div>
    </div>

    <CreateFolderModal
      :open="createFolderOpen"
      :parent-id="list.currentParentId"
      @close="createFolderOpen = false"
      @confirm="(name: string) => handleCreateFolder(name, list.currentParentId)"
    />

    <RenameModal
      :open="renameOpen"
      :file="renameTarget"
      @close="renameOpen = false"
      @confirm="handleRename"
    />

    <MoveCopyModal
      :open="list.moveCopyOpen"
      :type="list.moveCopyType"
      :files="list.moveCopyTargets"
      :folders="list.folders"
      @close="list.moveCopyOpen = false"
      @confirm="list.handleMoveCopyResult"
    />

    <BatchActionBar
      :selected-count="list.selectedIds.size"
      :has-mixed-source="list.hasMixedSelection"
      @move="list.openMoveCopy('move', list.selectedFiles)"
      @copy="list.openMoveCopy('copy', list.selectedFiles)"
      @download="list.handleBatchDownload"
      @share="list.openShareModal"
      @delete="list.handleBatchDelete"
      @clear="list.clearSelection"
    />

    <CreateShareModal
      :open="list.shareOpen"
      :item-ids="Array.from(list.selectedIds)"
      :edit-share="list.shareEditTarget"
      @close="list.shareOpen = false"
      @confirm="list.handleShareConfirm"
    />

    <FilePreviewModal
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
