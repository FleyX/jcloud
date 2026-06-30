<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { StorageSpaceVo, UserMigrationSubmitDto, UserMigrationTaskVo } from '@/types/storage-space'
import type { UserVo } from '@/types/auth'
import { getUserMigrationTask, submitUserMigration } from '@/api/user'
import { cn } from '@/utils/cn'
import { Truck, X } from '@lucide/vue'
import {
  DialogClose,
  DialogContent,
  DialogDescription,
  DialogOverlay,
  DialogPortal,
  DialogRoot,
  DialogTitle,
} from 'radix-vue'

interface Props {
  open: boolean
  user: (UserVo & { storageSpaceId?: string }) | null
  spaces: StorageSpaceVo[]
}

const props = defineProps<Props>()
const emit = defineEmits<{
  'update:open': [value: boolean]
  success: []
}>()

const localOpen = computed({
  get: () => props.open,
  set: (value) => emit('update:open', value),
})

const targetSpaceId = ref('')
const task = ref<UserMigrationTaskVo | null>(null)
const submitting = ref(false)
const loadingTask = ref(false)
let pollTimer: ReturnType<typeof setInterval> | null = null

const currentSpace = computed(() =>
  props.spaces.find((s) => s.id === props.user?.storageSpaceId) || null,
)

const targetSpaces = computed(() =>
  props.spaces.filter((s) => s.id !== props.user?.storageSpaceId && s.status === 1),
)

const canSubmit = computed(() => {
  return targetSpaceId.value && !isRunning.value
})

const isRunning = computed(() => {
  const status = task.value?.status
  return status === 'PENDING' || status === 'RUNNING'
})

watch(() => props.open, (open) => {
  if (open) {
    resetForm()
    loadLatestTask()
  } else {
    stopPolling()
    if (task.value?.status === 'COMPLETED') {
      resetForm()
    }
  }
})

function resetForm() {
  targetSpaceId.value = ''
  task.value = null
}

async function loadLatestTask() {
  if (!props.user) return
  loadingTask.value = true
  try {
    const latest = await getUserMigrationTask(props.user.id)
    task.value = latest
    if (isRunning.value) {
      startPolling()
    }
  } finally {
    loadingTask.value = false
  }
}

async function handleSubmit() {
  if (!props.user || !canSubmit.value) return
  submitting.value = true
  try {
    const dto: UserMigrationSubmitDto = {
      userId: props.user.id,
      targetSpaceId: targetSpaceId.value,
    }
    task.value = await submitUserMigration(props.user.id, dto)
    startPolling()
  } finally {
    submitting.value = false
  }
}

function startPolling() {
  stopPolling()
  pollTimer = setInterval(async () => {
    await pollTask()
  }, 2000)
}

function stopPolling() {
  if (pollTimer) {
    clearInterval(pollTimer)
    pollTimer = null
  }
}

async function pollTask() {
  if (!props.user) return
  const latest = await getUserMigrationTask(props.user.id)
  task.value = latest
  const status = latest?.status
  if (status === 'COMPLETED' || status === 'FAILED') {
    stopPolling()
    if (status === 'COMPLETED') {
      emit('success')
      emit('update:open', false)
    }
  }
}

function statusLabel(status?: string) {
  switch (status) {
    case 'PENDING': return '等待执行'
    case 'RUNNING': return '进行中'
    case 'COMPLETED': return '已完成'
    case 'FAILED': return '失败'
    default: return '无任务'
  }
}

function statusClass(status?: string) {
  return cn(
    'rounded-full px-2 py-0.5 text-xs font-medium',
    status === 'PENDING' && 'bg-amber-100 text-amber-700',
    status === 'RUNNING' && 'bg-primary-100 text-primary-700',
    status === 'COMPLETED' && 'bg-emerald-100 text-emerald-700',
    status === 'FAILED' && 'bg-red-100 text-red-700',
    !status && 'bg-surface-100 text-surface-500',
  )
}

function formatBytes(bytes: string | number | undefined): string {
  const size = Number(bytes ?? 0)
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

function formatQuota(bytes: string | number | undefined): string {
  const size = Number(bytes ?? 0)
  if (Number.isNaN(size) || size === 0) return '不限制'
  return formatBytes(size)
}
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
            <Truck class="h-5 w-5 text-primary-600" />
            <DialogTitle class="text-lg font-bold text-surface-900">
              迁移用户存储空间
            </DialogTitle>
          </div>
          <DialogClose
            class="rounded-lg p-1 text-surface-400 outline-none hover:bg-surface-100 hover:text-surface-600"
          >
            <X class="h-5 w-5" />
          </DialogClose>
        </div>
        <DialogDescription class="mb-4 text-sm text-surface-500">
          将用户 {{ user?.username }} 的文件迁移到新的存储空间
        </DialogDescription>

        <div class="mb-6 space-y-4">
          <div class="rounded-xl border border-surface-200 bg-surface-50 p-3 text-sm">
            <div class="flex justify-between py-1">
              <span class="text-surface-500">当前空间</span>
              <span class="font-medium text-surface-900">{{ currentSpace?.name || '-' }}</span>
            </div>
            <div class="flex justify-between py-1">
              <span class="text-surface-500">当前配额</span>
              <span class="font-medium text-surface-900">{{ formatQuota(user?.quota) }}</span>
            </div>
            <div class="flex justify-between py-1">
              <span class="text-surface-500">已用空间</span>
              <span class="font-medium text-surface-900">{{ formatBytes(user?.usedSpace) }}</span>
            </div>
          </div>

          <div>
            <label class="mb-1 block text-xs font-medium text-surface-700">目标存储空间</label>
            <select
              v-model="targetSpaceId"
              :disabled="isRunning"
              class="w-full rounded-xl border border-surface-200 bg-surface-50 px-3 py-2 text-sm outline-none focus:border-primary-500 focus:ring-2 focus:ring-primary-100 disabled:cursor-not-allowed disabled:opacity-60"
            >
              <option value="">
                请选择目标空间
              </option>
              <option
                v-for="space in targetSpaces"
                :key="space.id"
                :value="space.id"
              >
                {{ space.name }}（容量 {{ formatBytes(space.capacity) }}，可用 {{ formatBytes(space.freeSpace) }}）
              </option>
            </select>
          </div>

          <div
            v-if="task"
            class="rounded-xl border border-surface-200 bg-surface-50 p-3"
          >
            <div class="flex items-center justify-between">
              <span class="text-xs text-surface-500">最近一次迁移状态</span>
              <span :class="statusClass(task.status)">{{ statusLabel(task.status) }}</span>
            </div>
            <div
              v-if="task.errorMsg"
              class="mt-2 text-xs text-red-600"
            >
              {{ task.errorMsg }}
            </div>
          </div>
          <div
            v-else-if="loadingTask"
            class="text-xs text-surface-500"
          >
            加载任务状态中...
          </div>
        </div>

        <div class="flex justify-end gap-3">
          <DialogClose as-child>
            <button
              :disabled="submitting"
              class="rounded-xl border border-surface-200 bg-white px-4 py-2 text-sm font-medium text-surface-700 transition-colors hover:bg-surface-50 disabled:cursor-not-allowed disabled:opacity-60"
            >
              关闭
            </button>
          </DialogClose>
          <button
            :disabled="!canSubmit || submitting"
            class="rounded-xl bg-primary-600 px-4 py-2 text-sm font-medium text-white shadow-soft transition-colors hover:bg-primary-700 disabled:cursor-not-allowed disabled:opacity-70"
            @click="handleSubmit"
          >
            {{ submitting ? '提交中...' : '开始迁移' }}
          </button>
        </div>
      </DialogContent>
    </DialogPortal>
  </DialogRoot>
</template>
