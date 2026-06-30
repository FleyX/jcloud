import { useChunkedUpload } from './useChunkedUpload'
import { initChunkedUpload, preCheckUpload, tryInstantUpload } from '@/api/file'
import { identityHash } from '@/utils/fileHash'
import { useTransferStore } from '@/store/transfer'
import { useNotificationStore } from '@/store/notification'
import { createChunks } from '@/utils/chunk'
import type {
  BatchChunkedUploadInitItem,
  BatchUploadPreCheckItem,
  ChunkedUploadInitResponse,
  ConflictItemVo,
  ConflictStrategy,
  FileNodeVo,
  FileUploadPreCheckRequest,
  UploadPreCheckResult,
} from '@/types/file'

export type BatchConflictOpener = (conflicts: ConflictItemVo[]) => Promise<Record<string, ConflictStrategy> | null>

export interface UploadBatchFile {
  file: File
  relativePath?: string
}

export interface UploadBatchOptions {
  onComplete?: () => void
  openConflict?: BatchConflictOpener
  defaultConflictStrategy?: ConflictStrategy
}

function generateClientFileId(): string {
  return `client-${Date.now()}-${Math.random().toString(36).slice(2, 9)}`
}

interface BatchFileContext {
  clientFileId: string
  item: UploadBatchFile
  preCheckResult: UploadPreCheckResult
}

/**
 * 批量上传组合式函数。
 *
 * 对选中的多个文件统一进行批量冲突预检；若存在冲突则弹出批量冲突弹窗，
 * 支持每项单独选择 skip / overwrite / keep，也支持调用方通过
 * {@link UploadBatchOptions#defaultConflictStrategy} 指定默认策略并跳过弹窗。
 * 文件夹上传时通过 {@link UploadBatchFile#relativePath} 保留层级结构，
 * 后端会自动创建缺失的文件夹。
 */
export function useBatchUpload() {
  const transferStore = useTransferStore()
  const notificationStore = useNotificationStore()
  const { runUpload } = useChunkedUpload()

  async function uploadBatch(
    files: UploadBatchFile[],
    parentId = '0',
    options: UploadBatchOptions = {},
  ): Promise<void> {
    if (files.length === 0) return

    const { onComplete, openConflict, defaultConflictStrategy } = options
    const fileContexts = await buildFileContexts(files, parentId)

    const conflicts = collectConflicts(fileContexts)
    const strategyByClientId = await resolveConflicts(
      conflicts,
      fileContexts,
      openConflict,
      defaultConflictStrategy,
    )
    if (strategyByClientId === null) return

    const uploadContexts = fileContexts.filter((ctx) => strategyByClientId.get(ctx.clientFileId) !== 'skip')

    await handleInstantUploads(uploadContexts, parentId, strategyByClientId)

    const chunkedContexts = uploadContexts.filter((ctx) => !ctx.preCheckResult.candidates.length)
    if (chunkedContexts.length === 0) {
      onComplete?.()
      return
    }

    const initItems = await initChunkedUpload({
      items: chunkedContexts.map((ctx) => ({
        clientFileId: ctx.clientFileId,
        fileName: ctx.item.file.name,
        size: ctx.item.file.size,
        parentId,
        relativePath: ctx.item.relativePath,
      })),
    })

    const initResultByClientId = new Map<string, BatchChunkedUploadInitItem>()
    for (const item of initItems) {
      initResultByClientId.set(item.clientFileId, item)
    }

    await Promise.all(
      chunkedContexts.map(async (ctx) => {
        const initResult = initResultByClientId.get(ctx.clientFileId)
        if (!initResult || initResult.status === 'error' || !initResult.data) {
          notificationStore.error(initResult?.errorMessage || '初始化分片上传失败')
          transferStore.failTask(ctx.clientFileId)
          return
        }
        await startChunkedUpload(ctx, initResult.data, parentId, strategyByClientId, onComplete)
      }),
    )
  }

  async function buildFileContexts(
    files: UploadBatchFile[],
    parentId: string,
  ): Promise<BatchFileContext[]> {
    const items: FileUploadPreCheckRequest[] = await Promise.all(
      files.map(async (item) => {
        const partialHash = await identityHash(item.file)
        return {
          clientFileId: generateClientFileId(),
          fileName: item.file.name,
          size: item.file.size,
          parentId,
          relativePath: item.relativePath,
          partialHash: partialHash || undefined,
        }
      }),
    )

    const preCheckItems = await preCheckUpload({ items })
    const resultByClientId = new Map<string, BatchUploadPreCheckItem>()
    for (const item of preCheckItems) {
      resultByClientId.set(item.clientFileId, item)
    }

    return files.map((item, index) => {
      const clientFileId = items[index].clientFileId
      const preCheckResult = resultByClientId.get(clientFileId)
      return {
        clientFileId,
        item,
        preCheckResult: preCheckResult?.data ?? { conflicts: [], candidates: [] },
      }
    })
  }

  function collectConflicts(fileContexts: BatchFileContext[]): ConflictItemVo[] {
    const conflicts: ConflictItemVo[] = []
    for (const ctx of fileContexts) {
      if (ctx.preCheckResult.conflicts.length > 0) {
        const conflict = ctx.preCheckResult.conflicts[0]
        conflicts.push({
          sourceId: ctx.clientFileId,
          sourceName: ctx.item.file.name,
          sourceType: 'file',
          existingId: conflict.existingId,
          existingName: conflict.existingName,
          existingType: conflict.existingType,
        })
      }
    }
    return conflicts
  }

  async function resolveConflicts(
    conflicts: ConflictItemVo[],
    fileContexts: BatchFileContext[],
    openConflict?: BatchConflictOpener,
    defaultConflictStrategy?: ConflictStrategy,
  ): Promise<Map<string, ConflictStrategy> | null> {
    const strategyByClientId = new Map<string, ConflictStrategy>()

    for (const ctx of fileContexts) {
      if (ctx.preCheckResult.conflicts.length === 0) {
        strategyByClientId.set(ctx.clientFileId, defaultConflictStrategy ?? 'keep')
      }
    }

    if (conflicts.length === 0) {
      return strategyByClientId
    }

    if (defaultConflictStrategy) {
      for (const conflict of conflicts) {
        strategyByClientId.set(conflict.sourceId, defaultConflictStrategy)
      }
      return strategyByClientId
    }

    if (!openConflict) {
      for (const conflict of conflicts) {
        strategyByClientId.set(conflict.sourceId, 'keep')
      }
      return strategyByClientId
    }

    const chosen = await openConflict(conflicts)
    if (chosen === null) return null

    for (const conflict of conflicts) {
      strategyByClientId.set(conflict.sourceId, chosen[conflict.sourceId] ?? 'keep')
    }
    return strategyByClientId
  }

  async function handleInstantUploads(
    uploadContexts: BatchFileContext[],
    parentId: string,
    strategyByClientId: Map<string, ConflictStrategy>,
  ): Promise<void> {
    await Promise.all(
      uploadContexts
        .filter((ctx) => ctx.preCheckResult.candidates.length > 0)
        .map(async (ctx) => {
          const candidate = ctx.preCheckResult.candidates[0]
          const strategy = strategyByClientId.get(ctx.clientFileId)
          const instantNode = await tryInstantUpload(
            ctx.item.file,
            candidate,
            parentId,
            strategy,
            ctx.item.relativePath,
          )
          if (instantNode) {
            transferStore.completeTask(ctx.clientFileId)
          }
        }),
    )
  }

  async function startChunkedUpload(
    ctx: BatchFileContext,
    initData: ChunkedUploadInitResponse,
    parentId: string,
    strategyByClientId: Map<string, ConflictStrategy>,
    onComplete?: () => void,
  ): Promise<FileNodeVo | undefined> {
    transferStore.addUploadTask({
      fileId: ctx.clientFileId,
      fileName: ctx.item.file.name,
      totalBytes: ctx.item.file.size,
    })

    const task = transferStore.uploadQueue.find((item) => item.fileId === ctx.clientFileId)
    if (!task) return

    try {
      task.uploadId = initData.uploadId
      task.parentId = parentId
      task.file = ctx.item.file
      task.totalChunks = initData.totalChunks
      task.chunkSize = initData.chunkSize
      task.completedChunks = 0
      const chunks = await createChunks(ctx.item.file, initData.chunkSize)
      task.chunks = chunks.map((c) => ({
        index: c.index,
        size: c.size,
        hash: c.hash,
        status: 'waiting' as const,
      }))

      const strategy = strategyByClientId.get(ctx.clientFileId)
      const node = await runUpload(ctx.clientFileId, strategy)
      if (node) {
        transferStore.completeTask(ctx.clientFileId)
        notificationStore.success('上传成功')
        onComplete?.()
      }
      return node ?? undefined
    } catch (err) {
      if (task.status === 'paused') {
        return
      }
      const message = err instanceof Error ? err.message : '上传失败'
      transferStore.failTask(ctx.clientFileId)
      notificationStore.error(message)
      throw err
    }
  }

  return {
    uploadBatch,
  }
}
