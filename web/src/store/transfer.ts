import { defineStore } from 'pinia'
import { computed, ref } from 'vue'

/**
 * 上传任务状态
 * waiting  - 等待中
 * uploading- 上传中
 * paused   - 已暂停
 * success  - 已完成
 * error    - 失败
 */
export type UploadStatus = 'waiting' | 'uploading' | 'paused' | 'success' | 'error'

/**
 * 上传任务对象
 */
export interface UploadTask {
  fileId: string
  fileName: string
  progress: number // 0 ~ 100
  status: UploadStatus
  speed: string // 例如 "2.5 MB/s"
}

/**
 * 全局传输队列 Store
 * 用于管理上传/下载任务，是网盘核心状态之一
 */
export const useTransferStore = defineStore('transfer', () => {
  // ================= State =================
  const uploadQueue = ref<UploadTask[]>([])

  // ================= Getters =================
  /** 当前正在上传的任务 */
  const uploadingTasks = computed(() =>
    uploadQueue.value.filter((task) => task.status === 'uploading')
  )

  /** 是否还有未完成的任务 */
  const hasRunningTask = computed(() =>
    uploadQueue.value.some((task) => task.status === 'waiting' || task.status === 'uploading')
  )

  // ================= Actions =================
  /**
   * 添加上传任务
   * @param task 可只传部分字段，内部补充默认值
   */
  function addUploadTask(task: Partial<UploadTask> & { fileId: string; fileName: string }) {
    const newTask: UploadTask = {
      progress: 0,
      status: 'waiting',
      speed: '0 KB/s',
      ...task,
    }
    uploadQueue.value.unshift(newTask)
  }

  /**
   * 更新指定上传任务的进度与速度
   * @param fileId 任务唯一标识
   * @param progress 当前进度 0-100
   * @param speed 传输速度字符串
   */
  function updateProgress(fileId: string, progress: number, speed?: string) {
    const task = uploadQueue.value.find((item) => item.fileId === fileId)
    if (!task) return

    task.progress = Math.min(Math.max(progress, 0), 100)
    if (task.status === 'waiting' && task.progress > 0) {
      task.status = 'uploading'
    }
    if (speed) {
      task.speed = speed
    }
  }

  /**
   * 暂停/继续上传任务
   * 若当前为 uploading 则变为 paused；若为 paused/waiting 则变为 uploading
   * @param fileId 任务唯一标识
   */
  function pauseTask(fileId: string) {
    const task = uploadQueue.value.find((item) => item.fileId === fileId)
    if (!task) return

    if (task.status === 'uploading' || task.status === 'waiting') {
      task.status = 'paused'
    } else if (task.status === 'paused') {
      task.status = 'uploading'
    }
  }

  /**
   * 标记任务完成
   */
  function completeTask(fileId: string) {
    const task = uploadQueue.value.find((item) => item.fileId === fileId)
    if (!task) return
    task.status = 'success'
    task.progress = 100
    task.speed = '0 KB/s'
  }

  /**
   * 标记任务失败
   */
  function failTask(fileId: string) {
    const task = uploadQueue.value.find((item) => item.fileId === fileId)
    if (!task) return
    task.status = 'error'
  }

  /**
   * 从队列中移除任务
   */
  function removeTask(fileId: string) {
    const index = uploadQueue.value.findIndex((item) => item.fileId === fileId)
    if (index > -1) {
      uploadQueue.value.splice(index, 1)
    }
  }

  return {
    uploadQueue,
    uploadingTasks,
    hasRunningTask,
    addUploadTask,
    updateProgress,
    pauseTask,
    completeTask,
    failTask,
    removeTask,
  }
})
