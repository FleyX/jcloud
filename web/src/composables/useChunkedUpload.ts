import { watch } from 'vue'
import { useNotificationStore } from '@/store/notification'
import { useTransferStore, type UploadTask } from '@/store/transfer'
import {
  completeChunkedUpload,
  initChunkedUpload,
  listUploadedChunks,
  preCheckUpload,
  tryInstantUpload,
  uploadChunk,
} from '@/api/file'
import { createChunks } from '@/utils/chunk'
import { identityHash } from '@/utils/fileHash'
import type { ConflictItemVo, ConflictStrategy, FileNodeVo } from '@/types/file'

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

function updateTaskProgress(task: UploadTask, transferStore: ReturnType<typeof useTransferStore>) {
  const completed = task.chunks?.filter((c) => c.status === 'success').length ?? 0
  const total = task.totalChunks ?? 1
  const progress = Math.min(100, Math.round((completed / total) * 100))
  transferStore.updateProgress(task.fileId, progress)
}

function getTaskStatus(task: UploadTask): UploadTask['status'] {
  return task.status
}

async function waitForResume(task: UploadTask): Promise<void> {
  if (getTaskStatus(task) !== 'paused') return
  return new Promise((resolve, reject) => {
    const unwatch = watch(
      () => task.status,
      (status) => {
        if (status === 'uploading') {
          unwatch()
          resolve()
        }
        if (status === 'error' || status === 'success') {
          unwatch()
          reject(new Error('任务已结束'))
        }
      },
    )
  })
}

export type ConflictResolver = (item: ConflictItemVo) => Promise<ConflictStrategy | null>

/**
 * 分片上传组合式函数。
 *
 * 将文件按后端约定切分，支持暂停/继续、断点续传与进度展示。
 * 上传前会先进行冲突预检，若目标位置存在同名节点则通过 resolveConflict
 * 回调让用户选择处理方式；未提供回调时默认自动重命名。
 */
export function useChunkedUpload() {
  const transferStore = useTransferStore()
  const notificationStore = useNotificationStore()

  async function runUpload(taskId: string, strategy?: ConflictStrategy): Promise<FileNodeVo | undefined> {
    const task = transferStore.uploadQueue.find((item) => item.fileId === taskId)
    if (!task || !task.file || !task.uploadId || !task.chunks || !task.chunkSize) {
      return
    }

    const unwatchStatus = watch(
      () => task.status,
      (status) => {
        if (status === 'paused') {
          task.controller?.abort()
        }
      },
    )

    try {
      task.status = 'uploading'

      const uploadedIndexes = await listUploadedChunks(task.uploadId)
      uploadedIndexes.forEach((index) => {
        const chunkStatus = task.chunks!.find((c) => c.index === index)
        if (chunkStatus) {
          chunkStatus.status = 'success'
        }
      })
      task.completedChunks = task.chunks.filter((c) => c.status === 'success').length
      updateTaskProgress(task, transferStore)

      const chunks = await createChunks(task.file, task.chunkSize)
      let loadedBytes = chunks
        .filter((c) => task.chunks!.some((s) => s.index === c.index && s.status === 'success'))
        .reduce((sum, c) => sum + c.size, 0)
      const startTime = Date.now()
      let i = 0

      while (i < chunks.length) {
        const chunk = chunks[i]
        const chunkStatus = task.chunks.find((c) => c.index === chunk.index)
        if (!chunkStatus || chunkStatus.status === 'success') {
          i++
          continue
        }

        if (getTaskStatus(task) === 'paused') {
          await waitForResume(task)
        }
        if (getTaskStatus(task) !== 'uploading') {
          return
        }

        chunkStatus.status = 'uploading'
        const controller = new AbortController()
        task.controller = controller

        try {
          await uploadChunk(
            task.uploadId,
            chunk.index,
            chunk.hash,
            chunk.blob,
            (loaded) => {
              const currentLoaded = loadedBytes + loaded
              const elapsed = (Date.now() - startTime) / 1000
              const speedBps = elapsed > 0 ? Math.round(currentLoaded / elapsed) : 0
              const speed = formatSpeed(speedBps)
              const progress = Math.min(100, Math.round((currentLoaded / task.file!.size) * 100))
              transferStore.updateProgress(taskId, progress, speed, speedBps)
            },
            controller.signal,
          )
          chunkStatus.status = 'success'
          loadedBytes += chunk.size
          task.completedChunks = (task.completedChunks ?? 0) + 1
          updateTaskProgress(task, transferStore)
          i++
        } catch (err) {
          const message = err instanceof Error ? err.message : '上传分片失败'
          if (message === '上传已取消') {
            chunkStatus.status = 'waiting'
            if (getTaskStatus(task) === 'paused') {
              await waitForResume(task)
              if (getTaskStatus(task) !== 'uploading') {
                return
              }
            } else {
              return
            }
          } else {
            chunkStatus.status = 'error'
            task.status = 'error'
            throw err
          }
        } finally {
          task.controller = undefined
        }
      }

      const node = await completeChunkedUpload(task.uploadId, strategy)
      return node ?? undefined
    } finally {
      unwatchStatus()
    }
  }

  async function upload(
    file: File,
    parentId = '0',
    onComplete?: () => void,
    resolveConflict?: ConflictResolver,
  ): Promise<FileNodeVo | undefined> {
    const taskId = `up-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`
    transferStore.addUploadTask({ fileId: taskId, fileName: file.name, totalBytes: file.size })
    const task = transferStore.uploadQueue.find((item) => item.fileId === taskId)
    if (!task) return

    try {
      const partialHash = await identityHash(file)
      const preCheckResult = await preCheckUpload({
        fileName: file.name,
        size: file.size,
        parentId,
        partialHash: partialHash || undefined,
      })

      let strategy: ConflictStrategy | undefined
      if (preCheckResult.conflicts.length > 0) {
        if (resolveConflict) {
          const chosen = await resolveConflict(preCheckResult.conflicts[0])
          if (chosen === null) {
            transferStore.removeTask(taskId)
            return
          }
          strategy = chosen
        } else {
          strategy = 'keep'
        }
      }

      if (strategy === 'skip') {
        transferStore.removeTask(taskId)
        notificationStore.success('已跳过上传')
        return
      }

      if (preCheckResult.candidates.length > 0) {
        const instantNode = await tryInstantUpload(
          file,
          preCheckResult.candidates[0],
          parentId,
          strategy,
        )
        if (instantNode) {
          transferStore.completeTask(taskId)
          notificationStore.success('秒传成功')
          onComplete?.()
          return instantNode
        }
      }

      const initRes = await initChunkedUpload(file.name, file.size, parentId)
      task.uploadId = initRes.uploadId
      task.parentId = parentId
      task.file = file
      task.totalChunks = initRes.totalChunks
      task.chunkSize = initRes.chunkSize
      task.completedChunks = 0
      const chunks = await createChunks(file, initRes.chunkSize)
      task.chunks = chunks.map((c) => ({
        index: c.index,
        size: c.size,
        hash: c.hash,
        status: 'waiting' as const,
      }))

      const node = await runUpload(taskId, strategy)
      if (node) {
        transferStore.completeTask(taskId)
        notificationStore.success('上传成功')
        onComplete?.()
      }
      return node
    } catch (err) {
      if (task.status === 'paused') {
        return
      }
      const message = err instanceof Error ? err.message : '上传失败'
      transferStore.failTask(taskId)
      notificationStore.error(message)
      throw err
    }
  }

  return {
    upload,
  }
}
