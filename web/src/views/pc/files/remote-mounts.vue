<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import {
  fetchRemoteMountPage,
  deleteRemoteMount,
} from '@/api/remote-mount'
import { cn } from '@/utils/cn'
import { useConfirmStore } from '@/store/confirm'
import { useNotificationStore } from '@/store/notification'
import RemoteMountDialog from '@/components/remote-mounts/RemoteMountDialog.vue'
import RemoteMountSyncDialog from '@/components/remote-mounts/RemoteMountSyncDialog.vue'
import type { PageResult } from '@/types/auth'
import type { RemoteMountVo } from '@/types/remote-mount'
import {
  Globe,
  Plus,
  Pencil,
  RefreshCw,
  Trash2,
  ChevronLeft,
  ChevronRight,
  Search,
} from '@lucide/vue'
import { SwitchRoot, SwitchThumb } from 'radix-vue'

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
    message: `确定要删除挂载 "${mount.name}" 吗？本地文件树中的对应目录也会被移除。`,
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
  <div class="flex h-full flex-col gap-5">
    <div class="flex flex-col gap-4 rounded-2xl border border-surface-200 bg-white p-5 shadow-card sm:flex-row sm:items-center sm:justify-between">
      <div class="flex items-center gap-3">
        <div class="flex h-10 w-10 items-center justify-center rounded-xl bg-primary-100 text-primary-600">
          <Globe class="h-5 w-5" />
        </div>
        <div>
          <h2 class="text-lg font-bold text-surface-900">
            远程挂载
          </h2>
          <p class="text-xs text-surface-500">
            管理 WebDAV / S3 / NFS 等远程资源挂载
          </p>
        </div>
      </div>

      <div class="flex flex-wrap items-center gap-2">
        <div class="flex items-center gap-2 rounded-xl border border-surface-200 bg-surface-50 px-3 py-2">
          <Search class="h-4 w-4 text-surface-400" />
          <input
            v-model="query.name"
            type="text"
            placeholder="挂载名称"
            class="bg-transparent text-sm outline-none placeholder:text-surface-400"
            @keyup.enter="handleSearch"
          >
        </div>
        <button
          class="rounded-xl bg-primary-600 px-4 py-2 text-sm font-medium text-white shadow-soft transition-colors hover:bg-primary-700"
          @click="handleSearch"
        >
          查询
        </button>
        <button
          class="flex items-center gap-1 rounded-xl bg-emerald-600 px-4 py-2 text-sm font-medium text-white shadow-soft transition-colors hover:bg-emerald-700"
          @click="openCreateDialog"
        >
          <Plus class="h-4 w-4" />
          新增挂载
        </button>
      </div>
    </div>

    <div class="flex-1 overflow-hidden rounded-2xl border border-surface-200 bg-white shadow-card">
      <div class="overflow-x-auto">
        <table class="w-full text-left text-sm">
          <thead class="bg-surface-50 text-xs uppercase text-surface-500">
            <tr>
              <th class="px-5 py-3 font-medium">
                名称
              </th>
              <th class="px-5 py-3 font-medium">
                协议
              </th>
              <th class="px-5 py-3 font-medium">
                定时同步
              </th>
              <th class="px-5 py-3 font-medium">
                上次同步状态
              </th>
              <th class="px-5 py-3 font-medium">
                下次同步时间
              </th>
              <th class="px-5 py-3 font-medium">
                操作
              </th>
            </tr>
          </thead>
          <tbody class="divide-y divide-surface-100">
            <tr
              v-for="mount in pageData.records"
              :key="mount.id"
              class="hover:bg-surface-50/50"
            >
              <td class="px-5 py-3 font-medium text-surface-900">
                {{ mount.name }}
              </td>
              <td class="px-5 py-3 text-surface-600">
                {{ typeLabelMap[mount.type] || mount.type }}
              </td>
              <td class="px-5 py-3">
                <SwitchRoot
                  :checked="mount.enabled === 1"
                  disabled
                  class="relative h-6 w-11 cursor-not-allowed rounded-full bg-surface-200 outline-none opacity-60 transition-colors data-[state=checked]:bg-primary-600"
                >
                  <SwitchThumb
                    class="block h-5 w-5 translate-x-0.5 rounded-full bg-white shadow-sm transition-transform data-[state=checked]:translate-x-[22px]"
                  />
                </SwitchRoot>
              </td>
              <td class="px-5 py-3">
                <span :class="statusClass(mount.lastSyncStatus)">
                  {{ statusLabel(mount.lastSyncStatus) }}
                </span>
              </td>
              <td class="px-5 py-3 text-surface-500">
                {{ mount.nextSyncTime || '-' }}
              </td>
              <td class="px-5 py-3">
                <div class="flex items-center gap-2">
                  <button
                    class="flex items-center gap-1 rounded-lg bg-surface-100 px-2.5 py-1.5 text-xs font-medium text-surface-700 transition-colors hover:bg-surface-200"
                    @click="openEditDialog(mount)"
                  >
                    <Pencil class="h-3.5 w-3.5" />
                    编辑
                  </button>
                  <button
                    class="flex items-center gap-1 rounded-lg bg-emerald-50 px-2.5 py-1.5 text-xs font-medium text-emerald-600 transition-colors hover:bg-emerald-100"
                    @click="openSyncDialog(mount)"
                  >
                    <RefreshCw class="h-3.5 w-3.5" />
                    同步
                  </button>
                  <button
                    class="flex items-center gap-1 rounded-lg bg-red-50 px-2.5 py-1.5 text-xs font-medium text-red-600 transition-colors hover:bg-red-100"
                    @click="handleDelete(mount)"
                  >
                    <Trash2 class="h-3.5 w-3.5" />
                    删除
                  </button>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <p
        v-if="!loading && pageData.records.length === 0"
        class="px-5 py-12 text-center text-sm text-surface-500"
      >
        暂无远程挂载，点击右上角新增
      </p>

      <div class="flex items-center justify-between border-t border-surface-200 px-5 py-3">
        <span class="text-xs text-surface-500">
          共 {{ pageData.total }} 条，第 {{ pageData.current }} / {{ pageData.pages }} 页
        </span>
        <div class="flex items-center gap-2">
          <button
            :disabled="query.pageNum <= 1"
            :class="cn('rounded-lg border border-surface-200 p-1.5 text-surface-600 hover:bg-surface-50', query.pageNum <= 1 && 'cursor-not-allowed opacity-50')"
            @click="handlePageChange(query.pageNum - 1)"
          >
            <ChevronLeft class="h-4 w-4" />
          </button>
          <button
            :disabled="query.pageNum >= Number(pageData.pages)"
            :class="cn('rounded-lg border border-surface-200 p-1.5 text-surface-600 hover:bg-surface-50', query.pageNum >= Number(pageData.pages) && 'cursor-not-allowed opacity-50')"
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
