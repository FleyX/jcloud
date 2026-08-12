import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { cancelTransfer, createTransferCopy, createTransferMove, fetchRecentTransfers } from '@/api/transfer'
import { useUserStore } from '@/store/user'
import type { TransferCreateRequest, TransferTaskVo } from '@/types/transfer'

const ACTIVE_STATUSES = ['PENDING', 'RUNNING', 'CANCELLING']
const POLL_INTERVAL = 2000
/** 已处理任务 ID 的 localStorage 键（点击过的未成功任务，下次刷新不再展示） */
const HANDLED_STORAGE_KEY = 'jcloud-transfer-task-handled'
const HANDLED_STORAGE_CAP = 100

function isActive(task: TransferTaskVo): boolean {
  return ACTIVE_STATUSES.includes(task.status)
}

function loadHandledIds(): Set<string> {
  try {
    const raw = localStorage.getItem(HANDLED_STORAGE_KEY)
    const ids = raw ? (JSON.parse(raw) as string[]) : []
    return new Set(Array.isArray(ids) ? ids : [])
  } catch {
    return new Set()
  }
}

function saveHandledIds(ids: Set<string>): void {
  try {
    localStorage.setItem(HANDLED_STORAGE_KEY, JSON.stringify([...ids].slice(-HANDLED_STORAGE_CAP)))
  } catch {
    // 存储不可用时忽略
  }
}

/**
 * 跨来源传输任务状态：负责任务创建、进度轮询与取消。
 */
export const useTransferTaskStore = defineStore('transferTask', () => {
  const userStore = useUserStore()
  const tasks = ref<TransferTaskVo[]>([])
  /** 有任务到达终态时自增，文件列表可监听该值刷新 */
  const finishedTick = ref(0)
  /** 已被用户手动关闭（不再展示）的任务 ID，初始化时并入已处理任务 */
  const dismissedIds = ref<Set<string>>(loadHandledIds())
  /** 已处理（点击过）的未成功任务 ID，持久化到 localStorage，刷新后不再展示 */
  const handledIds = loadHandledIds()

  let timer: ReturnType<typeof setInterval> | null = null
  /** 各任务上次状态，用于检测到达终态 */
  const lastStatuses = new Map<string, string>()

  const visibleTasks = computed(() => tasks.value.filter((task) => !dismissedIds.value.has(task.id)))
  const activeCount = computed(() => visibleTasks.value.filter(isActive).length)

  async function refresh(): Promise<void> {
    const latest = await fetchRecentTransfers()
    for (const task of latest) {
      const previous = lastStatuses.get(task.id)
      if (previous && ACTIVE_STATUSES.includes(previous) && !isActive(task)) {
        finishedTick.value++
        // 跨来源传输终态（成功/部分成功/失败/取消）均可能写入字节，防抖刷新用户容量信息
        userStore.scheduleUserInfoRefresh()
      }
      lastStatuses.set(task.id, task.status)
    }
    tasks.value = latest
    if (latest.some(isActive)) {
      startPolling()
    } else {
      stopPolling()
    }
  }

  async function create(type: 'move' | 'copy', dto: TransferCreateRequest): Promise<TransferTaskVo> {
    const task = type === 'move' ? await createTransferMove(dto) : await createTransferCopy(dto)
    upsert(task)
    startPolling()
    return task
  }

  async function cancel(id: string): Promise<void> {
    await cancelTransfer(id)
    await refresh()
  }

  function dismiss(id: string): void {
    dismissedIds.value = new Set([...dismissedIds.value, id])
    handledIds.add(id)
    saveHandledIds(handledIds)
  }

  /**
   * 将未成功任务标记为已处理：当前页面仍展示，下次刷新后不再展示。
   *
   * @param id 任务 ID
   */
  function markHandled(id: string): void {
    handledIds.add(id)
    saveHandledIds(handledIds)
  }

  function upsert(task: TransferTaskVo): void {
    const index = tasks.value.findIndex((item) => item.id === task.id)
    if (index >= 0) {
      tasks.value[index] = task
    } else {
      tasks.value.unshift(task)
    }
    lastStatuses.set(task.id, task.status)
  }

  function startPolling(): void {
    if (timer !== null) return
    timer = setInterval(() => {
      refresh().catch(() => {
        // 轮询失败忽略，下轮重试
      })
    }, POLL_INTERVAL)
  }

  function stopPolling(): void {
    if (timer !== null) {
      clearInterval(timer)
      timer = null
    }
  }

  return {
    tasks,
    visibleTasks,
    activeCount,
    finishedTick,
    refresh,
    create,
    cancel,
    dismiss,
    markHandled,
  }
})
