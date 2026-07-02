<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import {
  fetchRemoteMountPage,
  deleteRemoteMount,
} from '@/api/remote-mount'
import { useConfirmStore } from '@/store/confirm'
import { useNotificationStore } from '@/store/notification'
import RemoteMountDialog from '@/components/remote-mounts/RemoteMountDialog.vue'
import RemoteMountSyncDialog from '@/components/remote-mounts/RemoteMountSyncDialog.vue'
import type { PageResult } from '@/types/auth'
import type { RemoteMountVo } from '@/types/remote-mount'
import { Plus, Search, Pencil, RefreshCw, Trash2, ChevronLeft, ChevronRight } from '@lucide/vue'
import { cn } from '@/utils/cn'

const query = reactive({
  name: '',
  pageNum: 1,
  pageSize: 10,
})

const pageData = ref<PageResult<RemoteMountVo>>({
  records: [],
  total: '0',
  size: '10',
  current: '1',
  pages: '0',
})
const loading = ref(false)
const submitting = ref(false)
const confirmStore = useConfirmStore()
const notificationStore = useNotificationStore()

const dialogOpen = ref(false)
const editingMount = ref<RemoteMountVo | null>(null)
const syncDialogOpen = ref(false)
const syncMount = ref<RemoteMountVo | null>(null)

const typeLabelMap: Record<string, string> = {
  webdav: 'WebDAV',
  s3: 'S3',
  nfs: 'NFS',
}

async function loadMounts() {
  loading.value = true
  try {
    pageData.value = await fetchRemoteMountPage(query)
  } finally {
    loading.value = false
  }
}

function handleSearch() {
  query.pageNum = 1
  loadMounts()
}

function handlePageChange(page: number) {
  query.pageNum = page
  loadMounts()
}

function openCreateDialog() {
  editingMount.value = null
  dialogOpen.value = true
}

function openEditDialog(mount: RemoteMountVo) {
  editingMount.value = mount
  dialogOpen.value = true
}

function openSyncDialog(mount: RemoteMountVo) {
  syncMount.value = mount
  syncDialogOpen.value = true
}

async function handleDelete(mount: RemoteMountVo) {
  const confirmed = await confirmStore.open({
    title: '删除远程挂载',
    message: `确定要删除挂载 "${mount.name}" 吗？`,
    confirmText: '删除',
    type: 'danger',
  })
  if (!confirmed) return
  submitting.value = true
  try {
    await deleteRemoteMount(mount.id)
    notificationStore.success('挂载已删除')
    await loadMounts()
  } finally {
    submitting.value = false
  }
}

function statusLabel(status?: string): string {
  switch (status) {
    case 'COMPLETED': return '已完成'
    case 'FAILED': return '失败'
    case 'PARTIAL': return '部分成功'
    default: return '未同步'
  }
}

function statusClass(status?: string): string {
  return cn(
    'rounded-full px-2 py-0.5 text-xs font-medium',
    status === 'COMPLETED' && 'bg-emerald-100 text-emerald-700',
    status === 'PARTIAL' && 'bg-amber-100 text-amber-700',
    status === 'FAILED' && 'bg-red-100 text-red-700',
    !status && 'bg-surface-100 text-surface-500',
  )
}

onMounted(loadMounts)
</script>

<template>
  <div class="flex h-full flex-col bg-surface-50">
    <div class="sticky top-0 z-10 border-b border-surface-200 bg-white/90 px-4 py-3 backdrop-blur-md">
      <div class="flex items-center gap-2">
        <div class="flex flex-1 items-center gap-2 rounded-xl border border-surface-200 bg-surface-50 px-3 py-2">
          <Search class="h-4 w-4 text-surface-400" />
          <input
            v-model="query.name"
            type="text"
            placeholder="搜索挂载名称..."
            class="flex-1 bg-transparent text-sm outline-none placeholder:text-surface-400"
            @keyup.enter="handleSearch"
          >
        </div>
        <button
          class="flex h-9 w-9 shrink-0 items-center justify-center rounded-xl bg-primary-600 text-white shadow-soft active:scale-95"
          @click="openCreateDialog"
        >
          <Plus class="h-5 w-5" />
        </button>
      </div>
    </div>

    <div class="flex-1 overflow-y-auto p-4">
      <div class="space-y-3">
        <div
          v-for="mount in pageData.records"
          :key="mount.id"
          class="rounded-2xl border border-surface-200 bg-white p-4 shadow-card"
        >
          <div class="flex items-start justify-between">
            <div>
              <p class="text-sm font-semibold text-surface-900">
                {{ mount.name }}
              </p>
              <p class="mt-0.5 text-xs text-surface-500">
                {{ typeLabelMap[mount.type] || mount.type }} · {{ mount.enabled === 1 ? '定时同步开启' : '未启用定时同步' }}
              </p>
            </div>
            <span :class="statusClass(mount.lastSyncStatus)">
              {{ statusLabel(mount.lastSyncStatus) }}
            </span>
          </div>

          <div class="mt-3 flex items-center justify-between text-xs text-surface-500">
            <span>下次同步：{{ mount.nextSyncTime || '-' }}</span>
          </div>

          <div class="mt-3 flex items-center justify-end gap-2">
            <button
              class="rounded-lg p-2 text-surface-500 hover:bg-surface-100"
              @click="openEditDialog(mount)"
            >
              <Pencil class="h-4 w-4" />
            </button>
            <button
              class="rounded-lg p-2 text-emerald-600 hover:bg-emerald-50"
              @click="openSyncDialog(mount)"
            >
              <RefreshCw class="h-4 w-4" />
            </button>
            <button
              class="rounded-lg p-2 text-red-500 hover:bg-red-50"
              @click="handleDelete(mount)"
            >
              <Trash2 class="h-4 w-4" />
            </button>
          </div>
        </div>
      </div>

      <p
        v-if="!loading && pageData.records.length === 0"
        class="py-12 text-center text-sm text-surface-500"
      >
        暂无远程挂载
      </p>

      <div class="mt-4 flex items-center justify-between">
        <span class="text-xs text-surface-500">
          共 {{ pageData.total }} 条
        </span>
        <div class="flex items-center gap-2">
          <button
            :disabled="query.pageNum <= 1"
            :class="cn('rounded-lg border border-surface-200 p-1.5 text-surface-600', query.pageNum <= 1 && 'opacity-50')"
            @click="handlePageChange(query.pageNum - 1)"
          >
            <ChevronLeft class="h-4 w-4" />
          </button>
          <span class="text-xs text-surface-600">{{ query.pageNum }} / {{ pageData.pages || 1 }}</span>
          <button
            :disabled="query.pageNum >= Number(pageData.pages)"
            :class="cn('rounded-lg border border-surface-200 p-1.5 text-surface-600', query.pageNum >= Number(pageData.pages) && 'opacity-50')"
            @click="handlePageChange(query.pageNum + 1)"
          >
            <ChevronRight class="h-4 w-4" />
          </button>
        </div>
      </div>
    </div>

    <RemoteMountDialog
      v-model:open="dialogOpen"
      :editing-mount="editingMount"
      @success="loadMounts"
    />

    <RemoteMountSyncDialog
      v-model:open="syncDialogOpen"
      :mount="syncMount"
    />
  </div>
</template>
