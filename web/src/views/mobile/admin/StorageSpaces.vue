<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import {
  createStorageSpace,
  deleteStorageSpace,
  fetchStorageSpacePage,
  fetchSystemStorageConfig,
  refreshStorageSpace,
  updateStorageSpace,
} from '@/api/storage-space'
import { useConfirmStore } from '@/store/confirm'
import { useNotificationStore } from '@/store/notification'
import StorageSpaceDialog from './components/StorageSpaceDialog.vue'
import SystemStorageConfigDialog from './components/SystemStorageConfigDialog.vue'
import type { PageResult } from '@/types/auth'
import type {
  StorageSpacePageQuery,
  StorageSpaceSaveDto,
  StorageSpaceUpdateDto,
  StorageSpaceVo,
  SystemStorageConfigVo,
} from '@/types/storage-space'
import { Database, Plus, Settings } from '@lucide/vue'

const query = reactive<StorageSpacePageQuery>({
  name: '',
  status: undefined,
  pageNum: 1,
  pageSize: 20,
})

const pageData = ref<PageResult<StorageSpaceVo>>({
  records: [],
  total: '0',
  size: '20',
  current: '1',
  pages: '0',
})
const loading = ref(false)
const submitting = ref(false)
const confirmStore = useConfirmStore()
const notificationStore = useNotificationStore()

const dialogOpen = ref(false)
const editingSpace = ref<StorageSpaceVo | null>(null)

const configDialogOpen = ref(false)
const systemConfig = ref<SystemStorageConfigVo>({})

async function loadSpaces() {
  loading.value = true
  try {
    const [data, config] = await Promise.all([
      fetchStorageSpacePage(query),
      fetchSystemStorageConfig(),
    ])
    pageData.value = data
    systemConfig.value = config
  } finally {
    loading.value = false
  }
}

function handleSearch() {
  query.pageNum = 1
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

function openConfigDialog() {
  configDialogOpen.value = true
}

async function handleConfigUpdated() {
  await loadSpaces()
}

async function handleRefresh(space: StorageSpaceVo) {
  submitting.value = true
  try {
    await refreshStorageSpace(space.id)
    notificationStore.success('存储空间信息已刷新')
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
    message: `确定要删除 ${space.name} 吗？`,
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
  <div class="flex h-full flex-col gap-3 p-4">
    <!-- Header -->
    <div class="flex items-center justify-between">
      <div class="flex items-center gap-2">
        <Database class="h-5 w-5 text-primary-600" />
        <h2 class="text-base font-bold text-surface-900">
          存储空间管理
        </h2>
      </div>
      <div class="flex items-center gap-2">
        <button
          class="flex h-8 w-8 items-center justify-center rounded-full bg-surface-100 text-surface-700 shadow-soft"
          @click="openConfigDialog"
        >
          <Settings class="h-4 w-4" />
        </button>
        <button
          class="flex h-8 w-8 items-center justify-center rounded-full bg-primary-600 text-white shadow-soft"
          @click="openCreateDialog"
        >
          <Plus class="h-4 w-4" />
        </button>
      </div>
    </div>

    <!-- Filters -->
    <div class="flex gap-2">
      <input
        v-model="query.name"
        type="text"
        placeholder="搜索名称"
        class="flex-1 rounded-xl border border-surface-200 bg-surface-50 px-3 py-2 text-sm outline-none focus:border-primary-500"
      >
      <button
        class="rounded-xl bg-primary-600 px-3 py-2 text-sm font-medium text-white"
        @click="handleSearch"
      >
        查询
      </button>
    </div>

    <!-- List -->
    <div class="flex-1 overflow-y-auto rounded-2xl border border-surface-200 bg-white shadow-card">
      <div
        v-for="space in pageData.records"
        :key="space.id"
        class="flex items-center justify-between border-b border-surface-100 p-4"
      >
        <div class="min-w-0 flex-1">
          <div class="flex items-center gap-2">
            <span class="truncate font-medium text-surface-900">{{ space.name }}</span>
            <span
              v-if="space.isPrimary === 1"
              class="rounded bg-primary-50 px-1.5 py-0.5 text-[10px] text-primary-600"
            >
              主空间
            </span>
            <span
              v-if="systemConfig.systemSpaceId === space.id"
              class="rounded bg-emerald-50 px-1.5 py-0.5 text-[10px] text-emerald-600"
            >
              系统目录
            </span>
          </div>
          <p class="mt-1 truncate text-xs text-surface-500">
            {{ space.path }}
          </p>
          <p class="mt-0.5 text-xs text-surface-500">
            容量 {{ formatBytes(space.capacity) }} · 已用 {{ formatBytes(space.usedSpace) }} · 剩余 {{ formatBytes(space.freeSpace) }}
          </p>
        </div>
        <div class="ml-3 flex flex-col gap-2">
          <button
            class="text-xs text-primary-600"
            @click="handleRefresh(space)"
          >
            刷新
          </button>
          <button
            class="text-xs text-primary-600"
            @click="openEditDialog(space)"
          >
            编辑
          </button>
          <button
            class="text-xs text-red-600"
            @click="handleDeleteSpace(space)"
          >
            删除
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

    <SystemStorageConfigDialog
      v-model:open="configDialogOpen"
      :config="systemConfig"
      @updated="handleConfigUpdated"
    />
  </div>
</template>
