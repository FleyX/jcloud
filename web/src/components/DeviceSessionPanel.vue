<script setup lang="ts">
/**
 * 登录设备管理面板
 * - 展示当前用户登录设备列表（设备名、最近活跃时间、当前设备标记）
 * - 支持踢出单个设备（带二次确认）
 * - 支持退出所有设备（带二次确认，执行后清除本地登录态并跳转登录页）
 */
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { Monitor, LogOut } from '@lucide/vue'
import { listDevices, logoutAll, revokeDevice } from '@/api/auth'
import { useUserStore } from '@/store/user'
import { useConfirmStore } from '@/store/confirm'
import { formatDate } from '@/utils/fileDisplay'
import type { DeviceSessionVo } from '@/types/auth'

const userStore = useUserStore()
const confirmStore = useConfirmStore()
const router = useRouter()

const devices = ref<DeviceSessionVo[]>([])
const loading = ref(true)
const loadError = ref(false)
const revokingId = ref<string | null>(null)
const loggingOutAll = ref(false)

async function loadDevices() {
  loading.value = true
  loadError.value = false
  try {
    devices.value = await listDevices(userStore.deviceId)
  } catch {
    loadError.value = true
  } finally {
    loading.value = false
  }
}

onMounted(loadDevices)

/** 最近活跃时间（epoch 毫秒）格式化为日期 */
function formatActiveTime(lastActiveTime?: number): string {
  if (lastActiveTime == null) return '-'
  return formatDate(new Date(lastActiveTime).toISOString())
}

async function handleRevoke(device: DeviceSessionVo) {
  const confirmed = await confirmStore.open({
    title: '踢出设备',
    message: '踢出后该设备需要重新登录',
    confirmText: '踢出',
    type: 'danger',
  })
  if (!confirmed) return
  revokingId.value = device.deviceId
  try {
    await revokeDevice(device.deviceId)
    devices.value = devices.value.filter((d) => d.deviceId !== device.deviceId)
  } finally {
    revokingId.value = null
  }
}

async function handleLogoutAll() {
  const confirmed = await confirmStore.open({
    title: '退出所有设备',
    message: '退出后所有设备的登录状态将被清除，包括当前设备',
    confirmText: '退出',
    type: 'danger',
  })
  if (!confirmed) return
  loggingOutAll.value = true
  try {
    await logoutAll()
    userStore.logoutAction()
    router.push('/login')
  } finally {
    loggingOutAll.value = false
  }
}
</script>

<template>
  <div class="rounded-2xl bg-white p-6 shadow-card">
    <div class="mb-1 flex items-center gap-2">
      <Monitor class="h-5 w-5 text-primary-600" />
      <h2 class="text-lg font-bold text-surface-900">
        登录设备
      </h2>
    </div>
    <p class="mb-5 text-sm text-surface-500">
      管理登录当前账号的所有设备
    </p>

    <!-- 加载中 -->
    <div
      v-if="loading"
      class="space-y-3"
    >
      <div class="h-10 animate-pulse rounded-xl bg-surface-100" />
      <div class="h-10 animate-pulse rounded-xl bg-surface-100" />
    </div>

    <!-- 加载失败 -->
    <div
      v-else-if="loadError"
      class="flex flex-col items-center gap-3 py-6"
    >
      <p class="text-sm text-surface-500">设备列表加载失败</p>
      <button
        class="rounded-xl border border-surface-200 px-4 py-2 text-sm font-medium text-surface-700 transition-colors hover:bg-surface-100"
        @click="loadDevices"
      >
        重试
      </button>
    </div>

    <!-- 空列表 -->
    <div
      v-else-if="devices.length === 0"
      class="py-6 text-center text-sm text-surface-500"
    >
      暂无其他登录设备
    </div>

    <!-- 设备列表 -->
    <ul
      v-else
      class="divide-y divide-surface-100"
    >
      <li
        v-for="device in devices"
        :key="device.deviceId"
        class="flex items-center justify-between gap-3 py-3"
      >
        <div class="min-w-0">
          <div class="flex items-center gap-2">
            <span class="truncate text-sm font-semibold text-surface-900">{{ device.deviceName }}</span>
            <span
              v-if="device.current"
              class="shrink-0 rounded-full bg-primary-100 px-2 py-0.5 text-xs text-primary-600"
            >
              当前设备
            </span>
          </div>
          <p class="mt-0.5 text-xs text-surface-500">
            最近活跃：{{ formatActiveTime(device.lastActiveTime) }}
          </p>
        </div>
        <button
          v-if="!device.current"
          :disabled="revokingId === device.deviceId"
          class="shrink-0 rounded-xl border border-surface-200 px-3 py-1.5 text-sm text-red-600 transition-colors hover:bg-surface-100 disabled:cursor-not-allowed disabled:opacity-70"
          @click="handleRevoke(device)"
        >
          {{ revokingId === device.deviceId ? '踢出中...' : '踢出' }}
        </button>
      </li>
    </ul>

    <!-- 退出所有设备 -->
    <div class="mt-5 flex justify-end border-t border-surface-100 pt-4">
      <button
        :disabled="loggingOutAll"
        class="inline-flex items-center gap-2 rounded-xl bg-red-600 px-4 py-2 text-sm font-medium text-white shadow-soft transition-colors hover:bg-red-700 disabled:cursor-not-allowed disabled:opacity-70"
        @click="handleLogoutAll"
      >
        <LogOut class="h-4 w-4" />
        {{ loggingOutAll ? '退出中...' : '退出所有设备' }}
      </button>
    </div>
  </div>
</template>
