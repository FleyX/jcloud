<script setup lang="ts">
/**
 * PC 公开分享访问页
 * - 无需登录，通过短码访问被分享的文件/文件夹
 * - 支持密码校验、文件夹导航、单文件下载/预览、批量下载
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
  fetchPublicShare,
  fetchPublicShareItems,
  fetchPublicBatchTaskStatus,
} from '@/api/share'
import { useNotificationStore } from '@/store/notification'
import PublicSharePreviewModal from './components/PublicSharePreviewModal.vue'
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

const displayItems = computed(() =>
  items.value.map((file) => ({
    ...file,
    iconType: inferType(file),
    displaySize: formatSize(file.size),
    selected: selectedIds.value.has(file.id),
  })),
)

const isAllSelected = computed(() => items.value.length > 0 && selectedIds.value.size === items.value.length)

onMounted(async () => {
  await loadShare()
})

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
    selectedIds.value = new Set(items.value.map((f) => f.id))
  }
}

function handleRowClick(file: FileNodeVo) {
  if (file.type === 'folder') {
    enterFolder(file)
  } else {
    openPreview(file)
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
</script>

<template>
  <div class="mx-auto h-full max-w-7xl">
    <!-- 顶部栏 -->
    <div class="mb-6 flex items-center justify-between">
      <div class="flex items-center gap-3">
        <button
          class="rounded-xl p-2 text-surface-500 transition-colors hover:bg-surface-100"
          @click="goBack"
        >
          <ArrowLeft class="h-5 w-5" />
        </button>
        <div>
          <h1 class="text-lg font-semibold text-surface-900">
            {{ share?.name || '分享内容' }}
          </h1>
          <p
            v-if="share?.description"
            class="line-clamp-1 text-sm text-surface-500"
          >
            {{ share.description }}
          </p>
        </div>
      </div>

      <div
        v-if="!needsPassword && items.length > 0"
        class="flex items-center gap-2"
      >
        <button
          :class="cn(
            'flex h-9 items-center gap-2 rounded-xl px-4 text-sm font-semibold shadow-soft transition-all active:scale-95',
            selectedIds.size > 0
              ? 'bg-primary-600 text-white hover:bg-primary-700'
              : 'cursor-not-allowed bg-surface-200 text-surface-400'
          )"
          :disabled="selectedIds.size === 0 || batchDownloading"
          @click="handleBatchDownload"
        >
          <Package class="h-4 w-4" />
          {{ batchDownloading ? '打包中...' : '批量下载' }}
        </button>
      </div>
    </div>

    <!-- 密码校验 -->
    <div
      v-if="needsPassword"
      class="flex flex-col items-center justify-center rounded-3xl border border-surface-200 bg-white py-16 shadow-soft"
    >
      <div class="mb-4 flex h-14 w-14 items-center justify-center rounded-2xl bg-amber-100 text-amber-600">
        <Lock class="h-7 w-7" />
      </div>
      <h2 class="mb-2 text-lg font-semibold text-surface-900">
        该分享需要密码
      </h2>
      <p class="mb-5 text-sm text-surface-500">
        请输入访问密码继续查看文件
      </p>
      <input
        v-model="password"
        type="password"
        placeholder="访问密码"
        class="mb-3 w-64 rounded-xl border border-surface-200 bg-surface-50 px-4 py-2.5 text-sm text-center outline-none transition-all focus:border-primary-300 focus:bg-white focus:ring-2 focus:ring-primary-100"
        @keyup.enter="submitPassword"
      >
      <p
        v-if="error"
        class="mb-3 text-xs text-red-500"
      >
        {{ error }}
      </p>
      <button
        class="rounded-xl bg-primary-600 px-5 py-2 text-sm font-semibold text-white shadow-soft transition-all hover:bg-primary-700 active:scale-95 disabled:cursor-not-allowed disabled:bg-surface-300"
        :disabled="!password.trim() || loading"
        @click="submitPassword"
      >
        确认
      </button>
    </div>

    <!-- 错误 -->
    <div
      v-else-if="error && !loading"
      class="rounded-3xl border border-surface-200 bg-white py-16 text-center text-sm text-red-500 shadow-soft"
    >
      {{ error }}
    </div>

    <!-- 文件列表 -->
    <div
      v-else-if="share"
      class="overflow-hidden rounded-3xl border border-surface-200 bg-white shadow-soft"
    >
      <!-- 面包屑 -->
      <div class="flex items-center gap-1 border-b border-surface-200 bg-surface-50/80 px-5 py-3 text-sm">
        <button
          v-for="(crumb, index) in breadcrumbStack"
          :key="index"
          :disabled="index === breadcrumbStack.length - 1"
          class="text-surface-600 hover:text-primary-600 disabled:cursor-default disabled:text-surface-900"
          @click="navigateToBreadcrumb(index)"
        >
          {{ crumb.name }}
          <span
            v-if="index < breadcrumbStack.length - 1"
            class="mx-1 text-surface-300"
          >/</span>
        </button>
      </div>

      <!-- 表头 -->
      <div
        class="grid grid-cols-[48px_1fr_140px_160px_100px] items-center border-b border-surface-200 bg-surface-50/80 px-5 py-3 text-xs font-semibold uppercase tracking-wider text-surface-500"
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
        <span>修改时间</span>
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
          v-for="file in displayItems"
          :key="file.id"
          :class="cn(
            'group grid cursor-pointer grid-cols-[48px_1fr_140px_160px_100px] items-center px-5 py-3.5 text-sm transition-all duration-200 ease-out-expo hover:bg-surface-50',
            file.selected && 'bg-primary-50/40 hover:bg-primary-50/60'
          )"
          @click="handleRowClick(file)"
        >
          <div
            class="flex items-center"
            @click.stop="toggleSelect(file.id)"
          >
            <button
              :class="cn(
                'flex h-4 w-4 items-center justify-center rounded border border-surface-300 bg-white transition-colors duration-200 hover:border-primary-400',
                file.selected && 'border-primary-500 bg-primary-500 text-white'
              )"
            >
              <Check
                v-if="file.selected"
                class="h-3 w-3"
              />
            </button>
          </div>

          <div class="flex min-w-0 items-center gap-3">
            <div
              :class="cn(
                'flex h-10 w-10 shrink-0 items-center justify-center rounded-xl transition-colors duration-200',
                file.iconType === 'image' && 'bg-purple-100 text-purple-600',
                file.iconType === 'video' && 'bg-rose-100 text-rose-600',
                file.iconType === 'audio' && 'bg-amber-100 text-amber-600',
                file.iconType === 'doc' && 'bg-blue-100 text-blue-600',
                file.iconType === 'folder' && 'bg-emerald-100 text-emerald-600'
              )"
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

          <span class="text-surface-500">{{ file.displaySize }}</span>

          <span class="text-surface-500">{{ file.updateTime || file.createTime }}</span>

          <div class="flex justify-end gap-1 opacity-0 transition-opacity group-hover:opacity-100">
            <button
              v-if="file.type === 'file'"
              class="rounded-lg p-1.5 text-surface-500 transition-colors hover:bg-surface-100"
              title="预览"
              @click.stop="openPreview(file)"
            >
              <Eye class="h-4 w-4" />
            </button>
            <button
              class="rounded-lg p-1.5 text-surface-500 transition-colors hover:bg-surface-100"
              title="下载"
              @click.stop="downloadSingle(file)"
            >
              <Download class="h-4 w-4" />
            </button>
          </div>
        </div>

        <p
          v-if="displayItems.length === 0"
          class="px-5 py-12 text-center text-sm text-surface-500"
        >
          该文件夹为空
        </p>
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
