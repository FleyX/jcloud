<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import {
  createStorageSpace,
  deleteStorageSpace,
  expandStorageSpace,
  fetchStorageSpacePage,
  updateStorageSpace,
} from '@/api/storage-space'
import { cn } from '@/utils/cn'
import { useConfirmStore } from '@/store/confirm'
import { useNotificationStore } from '@/store/notification'
import StorageSpaceDialog from './components/StorageSpaceDialog.vue'
import StorageSpaceExpandDialog from './components/StorageSpaceExpandDialog.vue'
import type { PageResult } from '@/types/auth'
import type {
  StorageSpaceExpandDto,
  StorageSpacePageQuery,
  StorageSpaceSaveDto,
  StorageSpaceUpdateDto,
  StorageSpaceVo,
} from '@/types/storage-space'
import {
  Database,
  Pencil,
  Trash2,
  ChevronLeft,
  ChevronRight,
  Plus,
  Maximize2,
} from '@lucide/vue'

const query = reactive<StorageSpacePageQuery>({
  name: '',
  type: undefined,
  status: undefined,
  pageNum: 1,
  pageSize: 10,
})

const pageData = ref<PageResult<StorageSpaceVo>>({
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
const editingSpace = ref<StorageSpaceVo | null>(null)

const expandDialogOpen = ref(false)
const expandingSpace = ref<StorageSpaceVo | null>(null)

async function loadSpaces() {
  loading.value = true
  try {
    const data = await fetchStorageSpacePage(query)
    pageData.value = data
  } finally {
    loading.value = false
  }
}

function handleSearch() {
  query.pageNum = 1
  loadSpaces()
}

function handlePageChange(page: number) {
  query.pageNum = page
  loadSpaces()
}

function openCreateDialog() {
  editingSpace.value = null
  dialogOpen.value = true
}

function openEditDialog(space: StorageSpaceVo) {
  editingSpace.value = space
  dialogOpen.value = true
}

function openExpandDialog(space: StorageSpaceVo) {
  expandingSpace.value = space
  expandDialogOpen.value = true
}

async function handleExpand(dto: StorageSpaceExpandDto) {
  submitting.value = true
  try {
    await expandStorageSpace(dto.id, dto)
    notificationStore.success('存储空间扩容成功')
    expandDialogOpen.value = false
    await loadSpaces()
  } catch {
    // request.ts 已统一处理异常提示
  } finally {
    submitting.value = false
  }
}

async function handleSubmit(dto: StorageSpaceSaveDto | StorageSpaceUpdateDto) {
  submitting.value = true
  try {
    if ('id' in dto) {
      await updateStorageSpace(dto.id, dto)
      notificationStore.success('存储空间更新成功')
    } else {
      await createStorageSpace(dto)
      notificationStore.success('存储空间创建成功')
    }
    dialogOpen.value = false
    await loadSpaces()
  } catch {
    // request.ts 已统一处理异常提示
  } finally {
    submitting.value = false
  }
}

async function handleDeleteSpace(space: StorageSpaceVo) {
  const confirmed = await confirmStore.open({
    title: '删除存储空间',
    message: `确定要删除存储空间 ${space.name} 吗？`,
    confirmText: '删除',
    type: 'danger',
  })
  if (!confirmed) return
  submitting.value = true
  try {
    await deleteStorageSpace(space.id)
    notificationStore.success('存储空间删除成功')
    await loadSpaces()
  } catch {
    // request.ts 已统一处理异常提示
  } finally {
    submitting.value = false
  }
}

function formatBytes(bytes: string): string {
  const size = Number(bytes)
  if (Number.isNaN(size)) return '-'
  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  let index = 0
  let value = size
  while (value >= 1024 && index < units.length - 1) {
    value /= 1024
    index++
  }
  return `${value.toFixed(2)} ${units[index]}`
}

onMounted(() => {
  loadSpaces()
})
</script>

<template>
  <div class="flex h-full flex-col gap-5">
    <!-- Header -->
    <div class="flex flex-col gap-4 rounded-2xl border border-surface-200 bg-white p-5 shadow-card sm:flex-row sm:items-center sm:justify-between">
      <div class="flex items-center gap-3">
        <div class="flex h-10 w-10 items-center justify-center rounded-xl bg-primary-100 text-primary-600">
          <Database class="h-5 w-5" />
        </div>
        <div>
          <h2 class="text-lg font-bold text-surface-900">
            存储空间管理
          </h2>
          <p class="text-xs text-surface-500">
            管理后端物理存储目录及容量配置
          </p>
        </div>
      </div>

      <div class="flex flex-wrap items-center gap-2">
        <input
          v-model="query.name"
          type="text"
          placeholder="名称"
          class="rounded-xl border border-surface-200 bg-surface-50 px-3 py-2 text-sm outline-none focus:border-primary-500 focus:ring-2 focus:ring-primary-100"
        >
        <select
          v-model="query.type"
          class="rounded-xl border border-surface-200 bg-surface-50 px-3 py-2 text-sm outline-none focus:border-primary-500 focus:ring-2 focus:ring-primary-100"
        >
          <option value="">
            全部类型
          </option>
          <option value="USER">
            用户存储空间
          </option>
          <option value="SYSTEM">
            系统存储空间
          </option>
        </select>
        <select
          v-model="query.status"
          class="rounded-xl border border-surface-200 bg-surface-50 px-3 py-2 text-sm outline-none focus:border-primary-500 focus:ring-2 focus:ring-primary-100"
        >
          <option :value="undefined">
            全部状态
          </option>
          <option :value="1">
            启用
          </option>
          <option :value="0">
            禁用
          </option>
        </select>
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
          新增存储空间
        </button>
      </div>
    </div>

    <!-- Table -->
    <div class="flex-1 overflow-hidden rounded-2xl border border-surface-200 bg-white shadow-card">
      <div class="overflow-x-auto">
        <table class="w-full text-left text-sm">
          <thead class="bg-surface-50 text-xs uppercase text-surface-500">
            <tr>
              <th class="px-5 py-3 font-medium">
                名称
              </th>
              <th class="px-5 py-3 font-medium">
                物理路径
              </th>
              <th class="px-5 py-3 font-medium">
                类型
              </th>
              <th class="px-5 py-3 font-medium">
                容量
              </th>
              <th class="px-5 py-3 font-medium">
                已用空间
              </th>
              <th class="px-5 py-3 font-medium">
                状态
              </th>
              <th class="px-5 py-3 font-medium">
                操作
              </th>
            </tr>
          </thead>
          <tbody class="divide-y divide-surface-100">
            <tr
              v-for="space in pageData.records"
              :key="space.id"
              class="hover:bg-surface-50/50"
            >
              <td class="px-5 py-3 font-medium text-surface-900">
                {{ space.name }}
              </td>
              <td class="px-5 py-3 font-mono text-xs text-surface-600">
                {{ space.path }}
              </td>
              <td class="px-5 py-3">
                <span
                  :class="cn(
                    'rounded-md px-2 py-0.5 text-xs',
                    space.type === 'USER' ? 'bg-blue-50 text-blue-600' : 'bg-purple-50 text-purple-600',
                  )"
                >
                  {{ space.type === 'USER' ? '用户' : '系统' }}
                </span>
              </td>
              <td class="px-5 py-3 text-surface-600">
                {{ formatBytes(space.capacity) }}
              </td>
              <td class="px-5 py-3 text-surface-600">
                {{ formatBytes(space.usedSpace) }}
              </td>
              <td class="px-5 py-3">
                <span
                  :class="cn(
                    'rounded-md px-2 py-0.5 text-xs',
                    space.status === 1 ? 'bg-emerald-50 text-emerald-600' : 'bg-red-50 text-red-600',
                  )"
                >
                  {{ space.status === 1 ? '启用' : '禁用' }}
                </span>
              </td>
              <td class="px-5 py-3">
                <div class="flex items-center gap-2">
                  <button
                    class="flex items-center gap-1 rounded-lg bg-primary-50 px-2.5 py-1.5 text-xs font-medium text-primary-600 transition-colors hover:bg-primary-100"
                    @click="openExpandDialog(space)"
                  >
                    <Maximize2 class="h-3.5 w-3.5" />
                    扩容
                  </button>
                  <button
                    class="flex items-center gap-1 rounded-lg bg-surface-100 px-2.5 py-1.5 text-xs font-medium text-surface-700 transition-colors hover:bg-surface-200"
                    @click="openEditDialog(space)"
                  >
                    <Pencil class="h-3.5 w-3.5" />
                    编辑
                  </button>
                  <button
                    class="flex items-center gap-1 rounded-lg bg-red-50 px-2.5 py-1.5 text-xs font-medium text-red-600 transition-colors hover:bg-red-100"
                    @click="handleDeleteSpace(space)"
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

      <!-- Pagination -->
      <div class="flex items-center justify-between border-t border-surface-200 px-5 py-3">
        <span class="text-xs text-surface-500">
          共 {{ pageData.total }} 条，第 {{ pageData.current }} / {{ pageData.pages }} 页
        </span>
        <div class="flex items-center gap-2">
          <button
            :disabled="query.pageNum! <= 1"
            :class="cn('rounded-lg border border-surface-200 p-1.5 text-surface-600 hover:bg-surface-50', query.pageNum! <= 1 && 'cursor-not-allowed opacity-50')"
            @click="handlePageChange(query.pageNum! - 1)"
          >
            <ChevronLeft class="h-4 w-4" />
          </button>
          <button
            :disabled="query.pageNum! >= Number(pageData.pages)"
            :class="cn('rounded-lg border border-surface-200 p-1.5 text-surface-600 hover:bg-surface-50', query.pageNum! >= Number(pageData.pages) && 'cursor-not-allowed opacity-50')"
            @click="handlePageChange(query.pageNum! + 1)"
          >
            <ChevronRight class="h-4 w-4" />
          </button>
        </div>
      </div>
    </div>

    <StorageSpaceDialog
      v-model:open="dialogOpen"
      :editing-space="editingSpace"
      :submitting="submitting"
      @submit="handleSubmit"
    />

    <StorageSpaceExpandDialog
      v-model:open="expandDialogOpen"
      :space="expandingSpace"
      :submitting="submitting"
      @submit="handleExpand"
    />
  </div>
</template>
