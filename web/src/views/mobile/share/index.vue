<script setup lang="ts">
/**
 * 移动端公开分享访问页
 */
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import {
  ArrowLeft,
  Check,
  Download,
  Eye,
  FileText,
  FolderUp,
  Image as ImageIcon,
  Film,
  Lock,
  Music,
  Package,
} from '@lucide/vue'
import { cn } from '@/utils/cn'
import {
  accessShareWithPassword,
  downloadPublicBatch,
  downloadPublicBatchResultUrl,
  downloadPublicFileUrl,
  fetchPublicBatchTaskStatus,
  fetchPublicShare,
  fetchPublicShareItems,
} from '@/api/share'
import { useNotificationStore } from '@/store/notification'
import PublicSharePreviewModal from '@/views/pc/share/components/PublicSharePreviewModal.vue'
import type { FileNodeVo } from '@/types/file'
import type { PublicShareVo } from '@/types/share'

const route = useRoute()
const notificationStore = useNotificationStore()

const code = computed(() => route.params.code as string)

const share = ref<PublicShareVo | null>(null)
const items = ref<FileNodeVo[]>([])
const loading = ref(false)
const error = ref('')
const needsPassword = ref(false)
const password = ref('')
const token = ref('')

const currentParentId = ref<string | undefined>(undefined)
const breadcrumbStack = ref<Array<{ id?: string; name: string }>>([{ name: '分享内容' }])
const selectedIds = ref<Set<string>>(new Set())
const previewOpen = ref(false)
const previewTarget = ref<FileNodeVo | null>(null)
const batchDownloading = ref(false)
const selectionMode = ref(false)

type FileType = 'image' | 'video' | 'audio' | 'doc' | 'folder'

const fileIconMap: Record<FileType, unknown> = {
  doc: FileText,
  image: ImageIcon,
  video: Film,
  audio: Music,
  folder: FolderUp,
}

function inferType(file: FileNodeVo): FileType {
  if (file.type === 'folder') return 'folder'
  const mime = file.mimeType || ''
  const ext = file.name.split('.').pop()?.toLowerCase() || ''
  if (mime.startsWith('image/') || ['png', 'jpg', 'jpeg', 'gif', 'webp', 'svg', 'bmp'].includes(ext)) return 'image'
  if (mime.startsWith('video/') || ['mp4', 'mov', 'avi', 'mkv', 'webm'].includes(ext)) return 'video'
  if (mime.startsWith('audio/') || ['mp3', 'wav', 'flac', 'aac', 'ogg'].includes(ext)) return 'audio'
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

const displayItems = computed(() =>
  items.value.map((file) => ({
    ...file,
    iconType: inferType(file),
    displaySize: formatSize(file.size),
    selected: selectedIds.value.has(file.id),
  })),
)

onMounted(loadShare)

async function loadShare() {
  loading.value = true
  error.value = ''
  try {
    share.value = await fetchPublicShare(code.value)
    if (share.value.hasPassword) {
      needsPassword.value = true
    } else {
      await loadItems()
    }
  } catch (err) {
    error.value = err instanceof Error ? err.message : '加载分享失败'
  } finally {
    loading.value = false
  }
}

async function loadItems() {
  loading.value = true
  try {
    items.value = await fetchPublicShareItems(code.value, {
      parentId: currentParentId.value,
      token: token.value || undefined,
    })
    selectedIds.value.clear()
  } catch (err) {
    error.value = err instanceof Error ? err.message : '加载文件列表失败'
  } finally {
    loading.value = false
  }
}

async function submitPassword() {
  if (!password.value.trim()) return
  loading.value = true
  try {
    token.value = await accessShareWithPassword(code.value, { password: password.value.trim() })
    needsPassword.value = false
    await loadItems()
  } catch (err) {
    error.value = err instanceof Error ? err.message : '密码校验失败'
  } finally {
    loading.value = false
  }
}

function enterFolder(file: FileNodeVo) {
  if (file.type !== 'folder') return
  currentParentId.value = file.id
  breadcrumbStack.value.push({ id: file.id, name: file.name })
  loadItems()
}

function navigateToBreadcrumb(index: number) {
  breadcrumbStack.value = breadcrumbStack.value.slice(0, index + 1)
  currentParentId.value = breadcrumbStack.value[index].id
  loadItems()
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

function toggleSelect(id: string) {
  if (selectedIds.value.has(id)) {
    selectedIds.value.delete(id)
  } else {
    selectedIds.value.add(id)
  }
}

function toggleSelectAll() {
  const allSelected = items.value.length > 0 && selectedIds.value.size === items.value.length
  if (allSelected) {
    selectedIds.value.clear()
  } else {
    selectedIds.value = new Set(items.value.map((f) => f.id))
  }
}

function openPreview(file: FileNodeVo) {
  previewTarget.value = file
  previewOpen.value = true
}

function downloadSingle(file: FileNodeVo) {
  const link = document.createElement('a')
  link.href = downloadPublicFileUrl(code.value, file.id, token.value)
  link.target = '_blank'
  document.body.appendChild(link)
  link.click()
  document.body.removeChild(link)
}

async function handleBatchDownload() {
  const ids = Array.from(selectedIds.value)
  if (ids.length === 0) return
  batchDownloading.value = true
  try {
    const task = await downloadPublicBatch(code.value, { ids }, token.value)
    if (task.status === 'completed') {
      downloadTaskResult(task.taskId)
      return
    }
    if (task.status === 'failed') {
      throw new Error(task.message || '批量下载失败')
    }
    await pollBatchTask(task.taskId)
  } catch (err) {
    const message = err instanceof Error ? err.message : '批量下载失败'
    notificationStore.error(message)
  } finally {
    batchDownloading.value = false
  }
}

async function pollBatchTask(taskId: string) {
  const maxAttempts = 120
  const intervalMs = 1000
  for (let attempt = 0; attempt < maxAttempts; attempt++) {
    await new Promise((resolve) => setTimeout(resolve, intervalMs))
    const task = await fetchPublicBatchTaskStatus(code.value, taskId, token.value)
    if (task.status === 'failed') {
      throw new Error(task.message || '批量下载失败')
    }
    if (task.status === 'completed') {
      downloadTaskResult(taskId)
      return
    }
  }
  throw new Error('批量下载任务超时')
}

function downloadTaskResult(taskId: string) {
  const link = document.createElement('a')
  link.href = downloadPublicBatchResultUrl(code.value, taskId, token.value)
  link.target = '_blank'
  document.body.appendChild(link)
  link.click()
  document.body.removeChild(link)
}

function goBack() {
  window.history.back()
}

function exitSelectionMode() {
  selectionMode.value = false
  selectedIds.value.clear()
}
</script>

<template>
  <div class="flex h-full flex-col bg-surface-50">
    <!-- 顶部栏 -->
    <div class="sticky top-0 z-10 border-b border-surface-200 bg-white/90 px-4 py-3 backdrop-blur-md">
      <div class="flex items-center gap-3">
        <button
          class="rounded-lg p-2 text-surface-500 hover:bg-surface-100"
          @click="goBack"
        >
          <ArrowLeft class="h-5 w-5" />
        </button>
        <div class="min-w-0 flex-1">
          <h1 class="truncate text-base font-semibold text-surface-900">
            {{ share?.name || '分享内容' }}
          </h1>
          <p
            v-if="share?.description"
            class="line-clamp-1 text-xs text-surface-500"
          >
            {{ share.description }}
          </p>
        </div>
        <button
          v-if="!needsPassword && items.length > 0"
          class="text-sm font-medium text-primary-600"
          @click="selectionMode = !selectionMode"
        >
          {{ selectionMode ? '完成' : '选择' }}
        </button>
      </div>
    </div>

    <!-- 密码校验 -->
    <div
      v-if="needsPassword"
      class="flex flex-1 flex-col items-center justify-center p-8"
    >
      <div class="mb-4 flex h-16 w-16 items-center justify-center rounded-2xl bg-amber-100 text-amber-600">
        <Lock class="h-8 w-8" />
      </div>
      <h2 class="mb-2 text-lg font-semibold text-surface-900">
        该分享需要密码
      </h2>
      <input
        v-model="password"
        type="password"
        placeholder="访问密码"
        class="mb-3 w-full max-w-xs rounded-xl border border-surface-200 bg-white px-4 py-3 text-center text-sm outline-none focus:border-primary-300 focus:ring-2 focus:ring-primary-100"
        @keyup.enter="submitPassword"
      >
      <p
        v-if="error"
        class="mb-3 text-xs text-red-500"
      >
        {{ error }}
      </p>
      <button
        class="w-full max-w-xs rounded-xl bg-primary-600 px-5 py-3 text-sm font-semibold text-white shadow-soft active:scale-95 disabled:bg-surface-300"
        :disabled="!password.trim() || loading"
        @click="submitPassword"
      >
        确认
      </button>
    </div>

    <!-- 错误 -->
    <div
      v-else-if="error && !loading"
      class="flex-1 p-8 text-center text-sm text-red-500"
    >
      {{ error }}
    </div>

    <!-- 文件列表 -->
    <div
      v-else-if="share"
      class="flex flex-1 flex-col overflow-hidden"
    >
      <!-- 面包屑 -->
      <div
        v-if="breadcrumbStack.length > 1"
        class="flex items-center gap-1 overflow-x-auto border-b border-surface-200 bg-white px-4 py-2 text-sm"
      >
        <button
          v-for="(crumb, index) in breadcrumbStack"
          :key="index"
          :disabled="index === breadcrumbStack.length - 1"
          class="whitespace-nowrap font-medium"
          :class="index === breadcrumbStack.length - 1 ? 'text-surface-900' : 'text-surface-500'"
          @click="navigateToBreadcrumb(index)"
        >
          {{ crumb.name }}
          <span
            v-if="index < breadcrumbStack.length - 1"
            class="mx-1 text-surface-300"
          >/</span>
        </button>
      </div>

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
            v-for="file in displayItems"
            :key="file.id"
            class="flex items-center gap-3 rounded-2xl border border-surface-200 bg-white p-4 shadow-card active:scale-[0.99]"
            @click="handleRowClick(file)"
          >
            <div
              v-if="selectionMode"
              class="flex h-6 w-6 shrink-0 items-center justify-center rounded border border-surface-300"
              :class="file.selected ? 'border-primary-500 bg-primary-500 text-white' : 'bg-white'"
              @click.stop="toggleSelect(file.id)"
            >
              <Check
                v-if="file.selected"
                class="h-4 w-4"
              />
            </div>

            <div
              :class="cn('flex h-12 w-12 shrink-0 items-center justify-center rounded-xl', getTypeStyle(file.iconType))"
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
                {{ file.displaySize }} · {{ file.updateTime || file.createTime }}
              </p>
            </div>

            <button
              v-if="!selectionMode && file.type === 'file'"
              class="rounded-lg p-2 text-surface-400 hover:bg-surface-100"
              @click.stop="openPreview(file)"
            >
              <Eye class="h-5 w-5" />
            </button>
            <button
              v-if="!selectionMode"
              class="rounded-lg p-2 text-surface-400 hover:bg-surface-100"
              @click.stop="downloadSingle(file)"
            >
              <Download class="h-5 w-5" />
            </button>
          </div>
        </div>

        <p
          v-if="!loading && displayItems.length === 0"
          class="py-10 text-center text-sm text-surface-500"
        >
          该文件夹为空
        </p>
      </div>

      <!-- 批量下载栏 -->
      <div
        v-if="selectionMode"
        class="border-t border-surface-200 bg-white px-4 pb-safe pt-2 shadow-soft"
      >
        <div class="flex items-center justify-between py-2">
          <div class="flex items-center gap-2">
            <button
              class="flex h-6 w-6 items-center justify-center rounded border border-surface-300"
              :class="items.length > 0 && selectedIds.size === items.length ? 'border-primary-500 bg-primary-500 text-white' : 'bg-white'"
              @click="toggleSelectAll"
            >
              <Check
                v-if="items.length > 0 && selectedIds.size === items.length"
                class="h-4 w-4"
              />
            </button>
            <span class="text-sm font-medium text-surface-700">
              已选 {{ selectedIds.size }} 项
            </span>
          </div>
          <button
            class="text-sm text-surface-500"
            @click="exitSelectionMode"
          >
            取消
          </button>
        </div>
        <button
          :disabled="selectedIds.size === 0 || batchDownloading"
          class="mb-3 flex w-full items-center justify-center gap-2 rounded-xl bg-primary-600 py-3 text-sm font-semibold text-white shadow-soft active:scale-95 disabled:bg-surface-300"
          @click="handleBatchDownload"
        >
          <Package class="h-4 w-4" />
          {{ batchDownloading ? '打包中...' : '批量下载' }}
        </button>
      </div>
    </div>

    <PublicSharePreviewModal
      v-model:open="previewOpen"
      :file="previewTarget"
      :code="code"
      :token="token"
    />
  </div>
</template>
