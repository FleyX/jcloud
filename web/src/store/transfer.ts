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

export type DownloadStatus = 'pending' | 'downloading' | 'success' | 'error'

/**
 * 分片状态
 */
export interface FileChunk {
  index: number
  size: number
  status: 'waiting' | 'uploading' | 'success' | 'error'
}

function formatSpeed(bytesPerSecond: number): string {
  if (bytesPerSecond <= 0) return '0 KB/s'
  const units = ['B/s', 'KB/s', 'MB/s', 'GB/s']
  let size = bytesPerSecond
  let i = 0
  while (size >= 1024 && i < units.length - 1) {
    size /= 1024
    i++
  }
  return `${size.toFixed(2)} ${units[i]}`
}

/**
 * 上传任务对象
 */
export interface UploadTask {
  fileId: string
  fileName: string
  progress: number // 0 ~ 100
  status: UploadStatus
  speed: string // 例如 "2.5 MB/s"
  totalBytes: number
  speedBps: number
  // 分片上传专用字段
  uploadId?: string
  parentId?: string
  file?: File
  totalChunks?: number
  chunkSize?: number
  completedChunks?: number
  chunks?: FileChunk[]
  controller?: AbortController
}

export interface DownloadProgress {
  progress: number
  loadedBytes?: number
  totalBytes?: number
  speedBps?: number
}

/**
 * 下载任务对象
 */
export interface DownloadTask {
  taskId: string
  fileName: string
  progress: number
  status: DownloadStatus
  message?: string
  totalBytes: number
  loadedBytes: number
  speedBps: number
  lastUpdateTime: number
  lastLoadedBytes: number
}

/**
 * 全局传输队列 Store
 * 用于管理上传/下载任务，是网盘核心状态之一
 */
export const useTransferStore = defineStore('transfer', () => {
  // ================= State =================
  const uploadQueue = ref<UploadTask[]>([])
  const downloadQueue = ref<DownloadTask[]>([])

  // ================= Getters =================
  /** 当前正在上传的任务 */
  const uploadingTasks = computed(() =>
    uploadQueue.value.filter((task) => task.status === 'uploading'),
  )

  /** 当前正在下载的任务 */
  const downloadingTasks = computed(() =>
    downloadQueue.value.filter((task) => task.status === 'pending' || task.status === 'downloading'),
  )

  /** 是否还有未完成的任务 */
  const hasRunningTask = computed(() =>
    uploadQueue.value.some((task) => task.status === 'waiting' || task.status === 'uploading') ||
    downloadQueue.value.some((task) => task.status === 'pending' || task.status === 'downloading'),
  )

  /** 等待中/传输中/已完成/失败 数量统计 */
  const taskStats = computed(() => {
    const waiting = uploadQueue.value.filter((t) => t.status === 'waiting').length +
      downloadQueue.value.filter((t) => t.status === 'pending').length
    const running = uploadQueue.value.filter((t) => t.status === 'uploading').length +
      downloadQueue.value.filter((t) => t.status === 'downloading').length
    const success = uploadQueue.value.filter((t) => t.status === 'success').length +
      downloadQueue.value.filter((t) => t.status === 'success').length
    const error = uploadQueue.value.filter((t) => t.status === 'error').length +
      downloadQueue.value.filter((t) => t.status === 'error').length
    return { waiting, running, success, error }
  })

  /** 当前传输总速度 */
  const overallSpeed = computed(() => {
    const uploadSpeed = uploadQueue.value
      .filter((t) => t.status === 'uploading')
      .reduce((sum, t) => sum + (t.speedBps || 0), 0)
    const downloadSpeed = downloadQueue.value
      .filter((t) => t.status === 'downloading')
      .reduce((sum, t) => sum + (t.speedBps || 0), 0)
    return formatSpeed(uploadSpeed + downloadSpeed)
  })

  /** 按字节加权的总体进度 */
  const overallProgress = computed(() => {
    const tasks = [...uploadQueue.value, ...downloadQueue.value]
    const total = tasks.reduce((sum, t) => sum + (t.totalBytes || 0), 0)
    if (total <= 0) return 0
    const loaded = tasks.reduce((sum, t) => {
      if ('fileId' in t) {
        return sum + Math.round((t.totalBytes || 0) * (t.progress / 100))
      }
      return sum + (t.loadedBytes || 0)
    }, 0)
    return Math.min(100, Math.round((loaded / total) * 100))
  })

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
      totalBytes: 0,
      speedBps: 0,
      ...task,
    }
    uploadQueue.value.unshift(newTask)
  }

  /**
   * 更新指定上传任务的进度与速度
   * @param fileId 任务唯一标识
   * @param progress 当前进度 0-100
   * @param speed 传输速度字符串
   * @param speedBps 传输速度字节/秒
   */
  function updateProgress(fileId: string, progress: number, speed?: string, speedBps?: number) {
    const task = uploadQueue.value.find((item) => item.fileId === fileId)
    if (!task) return

    task.progress = Math.min(Math.max(progress, 0), 100)
    if (task.status === 'waiting' && task.progress > 0) {
      task.status = 'uploading'
    }
    if (speed) {
      task.speed = speed
    }
    if (speedBps !== undefined) {
      task.speedBps = speedBps
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
      task.controller?.abort()
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
    task.speedBps = 0
  }

  /**
   * 标记任务失败
   */
  function failTask(fileId: string) {
    const task = uploadQueue.value.find((item) => item.fileId === fileId)
    if (!task) return
    task.status = 'error'
    task.speedBps = 0
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

  /**
   * 添加下载任务
   */
  function addDownloadTask(task: Partial<DownloadTask> & { taskId: string; fileName: string }) {
    const now = Date.now()
    const newTask: DownloadTask = {
      progress: 0,
      status: 'pending',
      totalBytes: 0,
      loadedBytes: 0,
      speedBps: 0,
      lastUpdateTime: now,
      lastLoadedBytes: 0,
      ...task,
    }
    downloadQueue.value.unshift(newTask)
  }

  /**
   * 更新下载任务进度
   */
  function updateDownloadProgress(taskId: string, progress: DownloadProgress) {
    const task = downloadQueue.value.find((item) => item.taskId === taskId)
    if (!task) return
    task.progress = Math.min(Math.max(progress.progress, 0), 100)
    if (task.status === 'pending') {
      task.status = 'downloading'
    }
    if (progress.totalBytes !== undefined) {
      task.totalBytes = progress.totalBytes
    }
    if (progress.loadedBytes !== undefined) {
      const now = Date.now()
      const deltaMs = now - task.lastUpdateTime
      if (deltaMs > 0) {
        const deltaBytes = progress.loadedBytes - task.lastLoadedBytes
        task.speedBps = Math.max(0, Math.round((deltaBytes / deltaMs) * 1000))
      }
      task.loadedBytes = progress.loadedBytes
      task.lastUpdateTime = now
      task.lastLoadedBytes = progress.loadedBytes
    }
    if (progress.speedBps !== undefined) {
      task.speedBps = progress.speedBps
    }
  }

  /**
   * 标记下载任务完成
   */
  function completeDownloadTask(taskId: string) {
    const task = downloadQueue.value.find((item) => item.taskId === taskId)
    if (!task) return
    task.status = 'success'
    task.progress = 100
    task.loadedBytes = task.totalBytes
    task.speedBps = 0
  }

  /**
   * 标记下载任务失败
   */
  function failDownloadTask(taskId: string, message?: string) {
    const task = downloadQueue.value.find((item) => item.taskId === taskId)
    if (!task) return
    task.status = 'error'
    task.message = message
    task.speedBps = 0
  }

  /**
   * 移除下载任务
   */
  function removeDownloadTask(taskId: string) {
    const index = downloadQueue.value.findIndex((item) => item.taskId === taskId)
    if (index > -1) {
      downloadQueue.value.splice(index, 1)
    }
  }

  return {
    uploadQueue,
    downloadQueue,
    uploadingTasks,
    downloadingTasks,
    hasRunningTask,
    taskStats,
    overallSpeed,
    overallProgress,
    addUploadTask,
    updateProgress,
    pauseTask,
    completeTask,
    failTask,
    removeTask,
    addDownloadTask,
    updateDownloadProgress,
    completeDownloadTask,
    failDownloadTask,
    removeDownloadTask,
  }
})
