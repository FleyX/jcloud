<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { UserVo } from '@/types/auth'
import type { UserSyncConfigUpdateDto, UserSyncConfigVo, UserSyncTaskVo } from '@/types/storage-space'
import { getUserSyncConfig, getUserSyncTask, submitUserSync, updateUserSyncConfig } from '@/api/user'
import { cn } from '@/utils/cn'
import { useNotificationStore } from '@/store/notification'
import { Clock, RefreshCw, X } from '@lucide/vue'
import {
  DialogClose,
  DialogContent,
  DialogDescription,
  DialogOverlay,
  DialogPortal,
  DialogRoot,
  DialogTitle,
  SwitchRoot,
  SwitchThumb,
} from 'radix-vue'

interface Props {
  open: boolean
  user: UserVo | null
}

const props = defineProps<Props>()
const emit = defineEmits<{
  'update:open': [value: boolean]
}>()

const localOpen = computed({
  get: () => props.open,
  set: (value) => emit('update:open', value),
})

const notificationStore = useNotificationStore()
const task = ref<UserSyncTaskVo | null>(null)
const config = ref<UserSyncConfigVo | null>(null)
const cronExpr = ref('')
const enabled = ref(false)
const loading = ref(false)
const submitting = ref(false)
const savingConfig = ref(false)
let pollTimer: ReturnType<typeof setInterval> | null = null

const isRunning = computed(() => {
  const status = task.value?.status
  return status === 'PENDING' || status === 'RUNNING'
})

watch(() => props.open, (open) => {
  if (open) {
    resetForm()
    loadData()
  } else {
    stopPolling()
  }
})

function resetForm() {
  task.value = null
  config.value = null
  cronExpr.value = ''
  enabled.value = false
}

async function loadData() {
  if (!props.user) return
  loading.value = true
  try {
    const [latestTask, syncConfig] = await Promise.all([
      getUserSyncTask(props.user.id),
      getUserSyncConfig(props.user.id),
    ])
    task.value = latestTask
    config.value = syncConfig
    cronExpr.value = syncConfig.cronExpr || ''
    enabled.value = syncConfig.enabled === 1
    if (isRunning.value) {
      startPolling()
    }
  } finally {
    loading.value = false
  }
}

async function handleImmediateSync() {
  if (!props.user || isRunning.value) return
  submitting.value = true
  try {
    task.value = await submitUserSync(props.user.id)
    startPolling()
  } finally {
    submitting.value = false
  }
}

async function handleSaveConfig() {
  if (!props.user) return
  savingConfig.value = true
  try {
    const dto: UserSyncConfigUpdateDto = {
      userId: props.user.id,
      cronExpr: cronExpr.value.trim(),
      enabled: enabled.value ? 1 : 0,
    }
    config.value = await updateUserSyncConfig(props.user.id, dto)
    notificationStore.success('定时同步配置已保存')
  } finally {
    savingConfig.value = false
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
  const latest = await getUserSyncTask(props.user.id)
  task.value = latest
  const status = latest?.status
  if (status === 'COMPLETED' || status === 'FAILED' || status === 'PARTIAL') {
    stopPolling()
  }
}

function statusLabel(status?: string) {
  switch (status) {
    case 'PENDING': return '等待执行'
    case 'RUNNING': return '同步中'
    case 'COMPLETED': return '已完成'
    case 'FAILED': return '失败'
    case 'PARTIAL': return '部分成功'
    default: return '无任务'
  }
}

function statusClass(status?: string) {
  return cn(
    'rounded-full px-2 py-0.5 text-xs font-medium',
    status === 'PENDING' && 'bg-amber-100 text-amber-700',
    status === 'RUNNING' && 'bg-primary-100 text-primary-700',
    status === 'COMPLETED' && 'bg-emerald-100 text-emerald-700',
    status === 'PARTIAL' && 'bg-amber-100 text-amber-700',
    status === 'FAILED' && 'bg-red-100 text-red-700',
    !status && 'bg-surface-100 text-surface-500',
  )
}

function formatTime(time?: string): string {
  return time || '-'
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
            <RefreshCw class="h-5 w-5 text-primary-600" />
            <DialogTitle class="text-lg font-bold text-surface-900">
              同步用户存储空间
            </DialogTitle>
          </div>
          <DialogClose
            class="rounded-lg p-1 text-surface-400 outline-none hover:bg-surface-100 hover:text-surface-600"
          >
            <X class="h-5 w-5" />
          </DialogClose>
        </div>
        <DialogDescription class="mb-4 text-sm text-surface-500">
          将用户 {{ user?.username }} 物理存储空间中的文件树同步到数据库
        </DialogDescription>

        <div class="mb-6 space-y-5">
          <!-- 立即同步 -->
          <div class="rounded-xl border border-surface-200 bg-surface-50 p-4">
            <div class="mb-3 flex items-center gap-2 text-sm font-medium text-surface-900">
              <RefreshCw class="h-4 w-4 text-primary-600" />
              立即同步
            </div>
            <div class="mb-3 flex items-center justify-between text-sm">
              <span class="text-surface-500">最近一次任务状态</span>
              <span :class="statusClass(task?.status)">{{ statusLabel(task?.status) }}</span>
            </div>
            <div
              v-if="task?.errorMsg"
              class="mb-3 rounded-lg bg-red-50 px-3 py-2 text-xs text-red-600"
            >
              {{ task.errorMsg }}
            </div>
            <div
              v-if="task?.status === 'COMPLETED' || task?.status === 'PARTIAL'"
              class="mb-3 text-xs text-surface-500"
            >
              成功 {{ task.successCount || 0 }} / 失败 {{ task.failCount || 0 }} / 总计 {{ task.totalCount || 0 }}
            </div>
            <button
              :disabled="isRunning || submitting || loading"
              class="w-full rounded-xl bg-primary-600 px-4 py-2 text-sm font-medium text-white shadow-soft transition-colors hover:bg-primary-700 disabled:cursor-not-allowed disabled:opacity-70"
              @click="handleImmediateSync"
            >
              {{ submitting ? '提交中...' : isRunning ? '同步中...' : '立即同步' }}
            </button>
          </div>

          <!-- 定时同步 -->
          <div class="rounded-xl border border-surface-200 bg-surface-50 p-4">
            <div class="mb-3 flex items-center gap-2 text-sm font-medium text-surface-900">
              <Clock class="h-4 w-4 text-primary-600" />
              定时同步
            </div>
            <div class="mb-3">
              <label class="mb-1 block text-xs font-medium text-surface-700">Cron 表达式</label>
              <input
                v-model="cronExpr"
                type="text"
                placeholder="例如 0 0 2 * * *"
                class="w-full rounded-xl border border-surface-200 bg-white px-3 py-2 text-sm outline-none focus:border-primary-500 focus:ring-2 focus:ring-primary-100"
              >
              <p class="mt-1 text-xs text-surface-400">
                参考：每天凌晨 2 点执行可填写 0 0 2 * * *
              </p>
            </div>
            <div class="mb-4 flex items-center justify-between">
              <span class="text-sm text-surface-700">启用定时同步</span>
              <SwitchRoot
                v-model:checked="enabled"
                class="relative h-6 w-11 cursor-pointer rounded-full bg-surface-200 outline-none transition-colors data-[state=checked]:bg-primary-600"
              >
                <SwitchThumb
                  class="block h-5 w-5 translate-x-0.5 rounded-full bg-white shadow-sm transition-transform data-[state=checked]:translate-x-[22px]"
                />
              </SwitchRoot>
            </div>
            <div
              v-if="config?.nextSyncTime"
              class="mb-3 text-xs text-surface-500"
            >
              下次同步时间：{{ formatTime(config.nextSyncTime) }}
            </div>
            <button
              :disabled="savingConfig || loading"
              class="w-full rounded-xl border border-primary-200 bg-white px-4 py-2 text-sm font-medium text-primary-600 transition-colors hover:bg-primary-50 disabled:cursor-not-allowed disabled:opacity-70"
              @click="handleSaveConfig"
            >
              {{ savingConfig ? '保存中...' : '保存配置' }}
            </button>
          </div>
        </div>

        <div class="flex justify-end">
          <DialogClose as-child>
            <button
              class="rounded-xl border border-surface-200 bg-white px-4 py-2 text-sm font-medium text-surface-700 transition-colors hover:bg-surface-50"
            >
              关闭
            </button>
          </DialogClose>
        </div>
      </DialogContent>
    </DialogPortal>
  </DialogRoot>
</template>
