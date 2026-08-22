<script setup lang="ts">
/**
 * 转码全局配置卡片（管理员）
 */
import { onMounted, ref } from 'vue'
import type { TranscodeHwaccel } from '@/types/media'
import { fetchTranscodeConfig, updateTranscodeConfig } from '@/api/media'
import { useNotificationStore } from '@/store/notification'

const notificationStore = useNotificationStore()

const hwaccelOptions: { value: TranscodeHwaccel; label: string }[] = [
  { value: 'vaapi', label: 'VA-API（Intel/AMD Linux）' },
  { value: 'qsv', label: 'Intel Quick Sync' },
  { value: 'nvenc', label: 'NVIDIA NVENC' },
  { value: 'none', label: '软解（CPU）' },
]

const hwaccel = ref<TranscodeHwaccel>('none')
const device = ref('')
const threads = ref(0)
const saving = ref(false)

onMounted(async () => {
  const config = await fetchTranscodeConfig()
  hwaccel.value = config.hwaccel
  device.value = config.device
  threads.value = config.threads
})

async function handleSave() {
  saving.value = true
  try {
    await updateTranscodeConfig({
      hwaccel: hwaccel.value,
      device: device.value.trim(),
      threads: Math.max(0, Math.floor(threads.value) || 0),
    })
    notificationStore.success('转码配置已保存')
  } catch {
    // request.ts 已统一处理异常提示
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <div class="rounded-2xl border border-surface-100 bg-white p-6 shadow-soft">
    <h3 class="mb-1 text-base font-semibold text-surface-900">
      转码配置
    </h3>
    <p class="mb-4 text-xs text-surface-400">
      网页端无法直接播放的视频由后端 ffmpeg 转码；系统首次启动已自动探测最优硬解方式，手动修改后以修改为准，硬解启动失败时播放会报错
    </p>
    <div class="flex flex-wrap items-end gap-4">
      <label class="w-64">
        <span class="mb-1 block text-xs font-medium text-surface-500">硬件加速方式</span>
        <select
          v-model="hwaccel"
          class="w-full rounded-xl border border-surface-200 bg-surface-50 px-4 py-2 text-sm outline-none focus:border-primary-300 focus:bg-white"
        >
          <option
            v-for="option in hwaccelOptions"
            :key="option.value"
            :value="option.value"
          >
            {{ option.label }}
          </option>
        </select>
      </label>
      <label class="w-64">
        <span class="mb-1 block text-xs font-medium text-surface-500">设备路径（VA-API）</span>
        <input
          v-model="device"
          type="text"
          placeholder="/dev/dri/renderD128"
          class="w-full rounded-xl border border-surface-200 bg-surface-50 px-4 py-2 text-sm outline-none focus:border-primary-300 focus:bg-white"
        >
      </label>
      <label class="w-40">
        <span class="mb-1 block text-xs font-medium text-surface-500">转码线程数</span>
        <input
          v-model.number="threads"
          type="number"
          min="0"
          placeholder="0 自动"
          class="w-full rounded-xl border border-surface-200 bg-surface-50 px-4 py-2 text-sm outline-none focus:border-primary-300 focus:bg-white"
        >
      </label>
      <button
        class="rounded-xl bg-primary-600 px-5 py-2 text-sm font-semibold text-white hover:bg-primary-700 disabled:bg-surface-300"
        :disabled="saving"
        @click="handleSave"
      >
        保存
      </button>
    </div>
  </div>
</template>
