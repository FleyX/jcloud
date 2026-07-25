<script setup lang="ts">
/**
 * 跨来源传输任务浮层。
 * 展示规则：页面加载时存在进行中任务，或最近一条任务未成功（失败/部分成功/已取消）时展示；
 * 本次页面会话内新发起或新结束的任务也会展示，出现后不自动关闭，只能手动关闭；
 * 未成功任务可点击展开失败明细（点击后标记为已处理，下次刷新不再展示）。
 */
import { computed, onMounted, ref, watch } from 'vue'
import { X, ArrowLeftRight, Ban, ChevronDown, ChevronUp } from '@lucide/vue'
import { cn } from '@/utils/cn'
import { useTransferTaskStore } from '@/store/transferTask'
import type { TransferTaskVo } from '@/types/transfer'

const taskStore = useTransferTaskStore()

/** 面板挂载时间：仅展示在此之后结束的成功任务 */
const mountTime = Date.now()

// 挂载时拉取一次最近任务，展示进行中和刚完成的传输
onMounted(() => {
  taskStore.refresh()
})

/** 展开失败明细的任务 ID */
const expandedIds = ref<Set<string>>(new Set())
/** 用户是否已手动关闭面板（本次页面会话内不再自动隐藏/出现，除非有新任务开始） */
const manuallyClosed = ref(false)

interface FailItem {
  name: string
  reason: string
}

function isActive(task: TransferTaskVo): boolean {
  return task.status === 'PENDING' || task.status === 'RUNNING' || task.status === 'CANCELLING'
}

function isUnsuccessful(task: TransferTaskVo): boolean {
  return task.status === 'FAILED' || task.status === 'PARTIAL' || task.status === 'CANCELED'
}

/** 本次会话内结束（页面加载后才有结束时间）的任务 */
function finishedThisSession(task: TransferTaskVo): boolean {
  if (task.status !== 'COMPLETED') return false
  return !!task.endTime && new Date(task.endTime).getTime() >= mountTime
}

/** 面板内展示的任务：进行中的 + 未成功的 + 本次会话内完成的（未手动关闭的） */
const displayTasks = computed(() =>
  taskStore.visibleTasks.filter((task) => isActive(task) || isUnsuccessful(task) || finishedThisSession(task)),
)

/** 展示条件：有任务可展示且未被手动关闭；出现后不自动隐藏 */
const visible = computed(() => {
  if (displayTasks.value.some(isActive)) return true
  return displayTasks.value.length > 0 && !manuallyClosed.value
})

// 有新任务进入进行中状态时，重新展开被手动关闭的面板
watch(
  () => taskStore.activeCount,
  (count) => {
    if (count > 0) {
      manuallyClosed.value = false
    }
  },
)

function progress(task: TransferTaskVo): number {
  const total = Number(task.totalCount)
  if (!total) return isActive(task) ? 0 : 100
  return Math.round(((Number(task.successCount) + Number(task.failCount)) / total) * 100)
}

function statusText(task: TransferTaskVo): string {
  switch (task.status) {
    case 'PENDING':
      return '等待中'
    case 'RUNNING':
      return `传输中 ${task.successCount}/${task.totalCount}`
    case 'CANCELLING':
      return '取消中'
    case 'CANCELED':
      return '已取消'
    case 'COMPLETED':
      return '已完成'
    case 'PARTIAL':
      return '部分完成'
    case 'FAILED':
      return '失败'
    default:
      return task.status
  }
}

function statusClass(task: TransferTaskVo): string {
  if (task.status === 'COMPLETED') return 'bg-emerald-500'
  if (task.status === 'FAILED' || task.status === 'CANCELED') return 'bg-red-500'
  if (task.status === 'PARTIAL') return 'bg-amber-500'
  return 'bg-primary-500'
}

/** 解析失败明细 JSON */
function failItems(task: TransferTaskVo): FailItem[] {
  if (!task.failDetail) return []
  try {
    const items = JSON.parse(task.failDetail) as FailItem[]
    return Array.isArray(items) ? items : []
  } catch {
    return []
  }
}

function toggleExpand(task: TransferTaskVo): void {
  if (!isUnsuccessful(task)) return
  // 点击过的未成功任务标记为已处理，下次刷新不再展示
  taskStore.markHandled(task.id)
  const next = new Set(expandedIds.value)
  if (next.has(task.id)) {
    next.delete(task.id)
  } else {
    next.add(task.id)
  }
  expandedIds.value = next
}

/** 关闭面板：关闭所有已结束的任务（进行中的任务结束后若未成功仍会按规则展示） */
function handleClose(): void {
  for (const task of displayTasks.value) {
    if (!isActive(task)) {
      taskStore.dismiss(task.id)
    }
  }
  manuallyClosed.value = true
}
</script>

<template>
  <div
    v-if="visible"
    class="fixed bottom-6 right-[22rem] z-50 w-80 overflow-hidden rounded-2xl border border-surface-200 bg-white shadow-soft"
  >
    <div class="flex items-center gap-2 px-4 py-3 text-sm font-medium text-surface-800">
      <ArrowLeftRight class="h-4 w-4 text-primary-500" />
      <span>跨来源传输</span>
      <span
        v-if="taskStore.activeCount > 0"
        class="text-xs text-surface-500"
      >{{ taskStore.activeCount }} 个进行中</span>
      <button
        title="关闭"
        class="ml-auto rounded-lg p-1 text-surface-400 hover:bg-surface-100"
        @click.stop="handleClose"
      >
        <X class="h-3.5 w-3.5" />
      </button>
    </div>
    <div class="max-h-72 space-y-3 overflow-y-auto border-t border-surface-100 px-4 py-3">
      <div
        v-for="task in displayTasks"
        :key="task.id"
        class="space-y-1.5"
      >
        <div
          class="flex items-center gap-2 text-xs text-surface-700"
          :class="isUnsuccessful(task) && 'cursor-pointer'"
          @click="toggleExpand(task)"
        >
          <span class="flex-1">
            {{ task.opType === 'move' ? '移动' : '复制' }}
            {{ task.sourceType === 'local' ? '本地' : '远程' }} → {{ task.targetType === 'local' ? '本地' : '远程' }}
          </span>
          <button
            v-if="isActive(task)"
            title="取消"
            class="rounded p-0.5 text-surface-400 hover:bg-surface-100 hover:text-surface-700"
            @click.stop="taskStore.cancel(task.id)"
          >
            <Ban class="h-3 w-3" />
          </button>
          <component
            :is="expandedIds.has(task.id) ? ChevronUp : ChevronDown"
            v-else-if="isUnsuccessful(task)"
            class="h-3 w-3 text-surface-400"
          />
          <span
            :class="
              cn(
                'text-xs',
                task.status === 'COMPLETED' && 'text-emerald-600',
                (task.status === 'FAILED' || task.status === 'CANCELED') && 'text-red-600',
                task.status === 'PARTIAL' && 'text-amber-600',
                isActive(task) && 'text-surface-500'
              )
            "
          >
            {{ statusText(task) }}
          </span>
        </div>
        <div class="h-1.5 w-full overflow-hidden rounded-full bg-surface-100">
          <div
            :class="cn('h-full rounded-full transition-all duration-300', statusClass(task))"
            :style="{ width: `${progress(task)}%` }"
          />
        </div>
        <template v-if="isUnsuccessful(task) && expandedIds.has(task.id)">
          <p
            v-if="task.errorMsg"
            class="text-xs text-red-500"
          >
            {{ task.errorMsg }}
          </p>
          <div
            v-if="failItems(task).length > 0"
            class="max-h-32 space-y-1 overflow-y-auto rounded-lg bg-red-50 px-2 py-1.5"
          >
            <p
              v-for="(item, index) in failItems(task)"
              :key="index"
              class="text-xs text-red-600"
            >
              <span class="font-medium">{{ item.name }}</span>
              <span class="text-red-400">：{{ item.reason }}</span>
            </p>
          </div>
          <p
            v-else-if="!task.errorMsg && Number(task.failCount) > 0"
            class="text-xs text-surface-500"
          >
            {{ task.failCount }} 个文件未传输成功
          </p>
        </template>
      </div>
    </div>
  </div>
</template>
