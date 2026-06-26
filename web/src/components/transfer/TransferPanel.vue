<script setup lang="ts">
/**
 * 传输任务浮层：展示上传/下载任务进度与状态
 * - 头部显示整体进度、总速度和任务统计
 * - 展开后展示每个任务的明细
 */
import { computed, ref } from 'vue'
import { X, Download, Upload, Pause, Play } from '@lucide/vue'
import { cn } from '@/utils/cn'
import { useTransferStore } from '@/store/transfer'

const transferStore = useTransferStore()
const expanded = ref(false)

const visibleTasks = computed(() => [
  ...transferStore.downloadQueue,
  ...transferStore.uploadQueue,
])

const runningCount = computed(() =>
  visibleTasks.value.filter((t) => t.status === 'pending' || t.status === 'downloading' || t.status === 'waiting' || t.status === 'uploading').length,
)

const stats = computed(() => transferStore.taskStats)

function statusText(status: string) {
  switch (status) {
    case 'pending':
    case 'waiting':
      return '等待中'
    case 'downloading':
    case 'uploading':
      return '传输中'
    case 'paused':
      return '已暂停'
    case 'success':
      return '已完成'
    case 'error':
      return '失败'
    default:
      return status
  }
}

function statusClass(status: string) {
  switch (status) {
    case 'success':
      return 'bg-emerald-500'
    case 'error':
      return 'bg-red-500'
    default:
      return 'bg-primary-500'
  }
}

</script>

<template>
  <div
    v-if="visibleTasks.length > 0"
    class="fixed bottom-6 right-6 z-50 w-80 overflow-hidden rounded-2xl border border-surface-200 bg-white shadow-soft"
  >
    <div
      class="flex cursor-pointer items-center justify-between px-4 py-3"
      @click="expanded = !expanded"
    >
      <div class="flex flex-1 flex-col gap-1">
        <div class="flex items-center gap-2 text-sm font-medium text-surface-800">
          <span v-if="runningCount > 0">传输中 ({{ runningCount }})</span>
          <span v-else>传输完成</span>
          <span class="text-xs text-surface-500">{{ transferStore.overallSpeed }}</span>
        </div>
        <div class="h-1.5 w-full overflow-hidden rounded-full bg-surface-100">
          <div
            class="h-full rounded-full bg-primary-500 transition-all duration-300"
            :style="{ width: `${transferStore.overallProgress}%` }"
          />
        </div>
        <div class="flex items-center gap-2 text-[10px] text-surface-500">
          <span>等待 {{ stats.waiting }}</span>
          <span>传输中 {{ stats.running }}</span>
          <span>已完成 {{ stats.success }}</span>
          <span>失败 {{ stats.error }}</span>
        </div>
      </div>
      <button
        class="ml-3 rounded-lg p-1 text-surface-400 hover:bg-surface-100"
        @click.stop="visibleTasks.forEach((t) => 'taskId' in t ? transferStore.removeDownloadTask(t.taskId) : transferStore.removeTask(t.fileId))"
      >
        <X class="h-4 w-4" />
      </button>
    </div>

    <div
      v-if="expanded"
      class="max-h-72 space-y-3 overflow-y-auto border-t border-surface-100 px-4 py-3"
    >
      <div
        v-for="task in visibleTasks"
        :key="'taskId' in task ? task.taskId : task.fileId"
        class="space-y-1.5"
      >
        <div class="flex items-center gap-2 text-xs text-surface-700">
          <Download
            v-if="'taskId' in task"
            class="h-3.5 w-3.5 text-surface-400"
          />
          <Upload
            v-else
            class="h-3.5 w-3.5 text-surface-400"
          />
          <span class="line-clamp-1 flex-1">{{ task.fileName }}</span>
          <button
            v-if="!('taskId' in task) && (task.status === 'uploading' || task.status === 'waiting' || task.status === 'paused')"
            class="rounded p-0.5 text-surface-400 hover:bg-surface-100 hover:text-surface-700"
            @click.stop="transferStore.pauseTask(task.fileId)"
          >
            <Pause
              v-if="task.status !== 'paused'"
              class="h-3 w-3"
            />
            <Play
              v-else
              class="h-3 w-3"
            />
          </button>
          <span
            :class="
              cn(
                'text-xs',
                task.status === 'success' && 'text-emerald-600',
                task.status === 'error' && 'text-red-600',
                (task.status === 'downloading' || task.status === 'uploading' || task.status === 'pending' || task.status === 'waiting' || task.status === 'paused') && 'text-surface-500'
              )
            "
          >
            {{ statusText(task.status) }}
          </span>
        </div>
        <div class="h-1.5 w-full overflow-hidden rounded-full bg-surface-100">
          <div
            :class="cn('h-full rounded-full transition-all duration-300', statusClass(task.status))"
            :style="{ width: `${task.progress}%` }"
          />
        </div>
        <p
          v-if="task.status === 'error' && 'message' in task && task.message"
          class="text-xs text-red-500"
        >
          {{ task.message }}
        </p>
      </div>
    </div>
  </div>
</template>
