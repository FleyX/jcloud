<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { fetchStorageSpacePage, updateSystemStorageConfig } from '@/api/storage-space'
import { useNotificationStore } from '@/store/notification'
import { cn } from '@/utils/cn'
import { Database, X } from '@lucide/vue'
import {
  DialogClose,
  DialogContent,
  DialogDescription,
  DialogOverlay,
  DialogPortal,
  DialogRoot,
  DialogTitle,
} from 'radix-vue'
import type { PageResult } from '@/types/auth'
import type { StorageSpacePageQuery, StorageSpaceVo, SystemStorageConfigVo } from '@/types/storage-space'

interface Props {
  open: boolean
  config: SystemStorageConfigVo | null
}

const props = defineProps<Props>()
const emit = defineEmits<{
  'update:open': [value: boolean]
  updated: []
}>()

const notificationStore = useNotificationStore()
const spaces = ref<StorageSpaceVo[]>([])
const loading = ref(false)
const submitting = ref(false)
const selectedId = ref<string>('')

const query = computed<StorageSpacePageQuery>(() => ({
  status: 1,
  pageNum: 1,
  pageSize: 1000,
}))

const localOpen = computed({
  get: () => props.open,
  set: (value) => emit('update:open', value),
})

watch(() => props.config, (config) => {
  selectedId.value = config?.systemSpaceId || ''
}, { immediate: true })

async function loadSpaces() {
  loading.value = true
  try {
    const data: PageResult<StorageSpaceVo> = await fetchStorageSpacePage(query.value)
    spaces.value = data.records
  } finally {
    loading.value = false
  }
}

async function handleSubmit() {
  if (!selectedId.value) {
    notificationStore.error('请选择用户存储空间')
    return
  }
  submitting.value = true
  try {
    await updateSystemStorageConfig({ systemSpaceId: selectedId.value })
    notificationStore.success('系统数据目录配置成功')
    emit('updated')
    emit('update:open', false)
  } catch {
    // request.ts 已统一处理异常提示
  } finally {
    submitting.value = false
  }
}

onMounted(() => {
  loadSpaces()
})
</script>

<template>
  <DialogRoot v-model:open="localOpen">
    <DialogPortal>
      <DialogOverlay class="fixed inset-0 z-40 bg-surface-900/40 backdrop-blur-sm" />
      <DialogContent
        class="fixed left-1/2 top-1/2 z-50 w-full max-w-md -translate-x-1/2 -translate-y-1/2 rounded-2xl bg-white p-6 shadow-soft"
      >
        <div class="mb-4 flex items-center justify-between">
          <div class="flex items-center gap-2">
            <Database class="h-5 w-5 text-primary-600" />
            <DialogTitle class="text-lg font-bold text-surface-900">
              配置系统目录
            </DialogTitle>
          </div>
          <DialogClose
            class="rounded-lg p-1 text-surface-400 outline-none hover:bg-surface-100 hover:text-surface-600"
          >
            <X class="h-5 w-5" />
          </DialogClose>
        </div>
        <DialogDescription class="mb-4 text-sm text-surface-500">
          选择一个用户存储空间，系统数据将存放于其 /system 子目录下。
        </DialogDescription>

        <div class="mb-6 max-h-80 overflow-y-auto rounded-xl border border-surface-200">
          <div
            v-for="space in spaces"
            :key="space.id"
            :class="cn(
              'cursor-pointer border-b border-surface-100 p-4 transition-colors last:border-b-0 hover:bg-surface-50',
              selectedId === space.id && 'bg-primary-50 hover:bg-primary-50',
            )"
            @click="selectedId = space.id"
          >
            <div class="flex items-center justify-between">
              <span class="font-medium text-surface-900">{{ space.name }}</span>
              <span
                v-if="config?.systemSpaceId === space.id"
                class="rounded-md bg-emerald-50 px-2 py-0.5 text-xs text-emerald-600"
              >
                当前配置
              </span>
            </div>
            <p class="mt-1 text-xs text-surface-500">
              {{ space.path }}
            </p>
          </div>
          <div
            v-if="!loading && spaces.length === 0"
            class="p-4 text-center text-sm text-surface-500"
          >
            暂无用户存储空间
          </div>
          <div
            v-if="loading"
            class="p-4 text-center text-sm text-surface-500"
          >
            加载中...
          </div>
        </div>

        <div class="flex justify-end gap-3">
          <DialogClose as-child>
            <button
              :disabled="submitting"
              class="rounded-xl border border-surface-200 bg-white px-4 py-2 text-sm font-medium text-surface-700 transition-colors hover:bg-surface-50 disabled:cursor-not-allowed disabled:opacity-60"
            >
              取消
            </button>
          </DialogClose>
          <button
            :disabled="submitting || !selectedId"
            class="rounded-xl bg-primary-600 px-4 py-2 text-sm font-medium text-white shadow-soft transition-colors hover:bg-primary-700 disabled:cursor-not-allowed disabled:opacity-70"
            @click="handleSubmit"
          >
            {{ submitting ? '保存中...' : '保存' }}
          </button>
        </div>
      </DialogContent>
    </DialogPortal>
  </DialogRoot>
</template>
