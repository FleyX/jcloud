import { useChunkedUpload } from './useChunkedUpload'
import { preCheckUpload } from '@/api/file'
import { identityHash } from '@/utils/fileHash'
import type { ConflictItemVo, ConflictStrategy } from '@/types/file'

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

function generateConflictId(): string {
  return `conflict-${Date.now()}-${Math.random().toString(36).slice(2, 9)}`
}

/**
 * 批量上传组合式函数。
 *
 * 对选中的多个文件先统一进行冲突预检；若存在冲突则弹出批量冲突弹窗，
 * 支持每项单独选择 skip / overwrite / keep，也支持调用方通过
 * {@link UploadBatchOptions#defaultConflictStrategy} 指定默认策略并跳过弹窗。
 * 文件夹上传时通过 {@link UploadBatchFile#relativePath} 保留层级结构，
 * 后端会自动创建缺失的文件夹。
 */
export function useBatchUpload() {
  const { upload } = useChunkedUpload()

  async function uploadBatch(
    files: UploadBatchFile[],
    parentId = '0',
    options: UploadBatchOptions = {},
  ): Promise<void> {
    if (files.length === 0) return

    const { onComplete, openConflict, defaultConflictStrategy } = options

    // 单文件沿用原有单条上传流程
    if (files.length === 1) {
      const item = files[0]
      const strategy = await preCheckSingle(item, parentId, openConflict, defaultConflictStrategy)
      if (strategy === 'skip') return
      await upload(
        item.file,
        parentId,
        item.relativePath,
        onComplete,
        strategy ? async () => strategy : undefined,
      )
      return
    }

    const conflicts: ConflictItemVo[] = []
    const strategyByFile = new Map<UploadBatchFile, ConflictStrategy>()

    for (const item of files) {
      const partialHash = await identityHash(item.file)
      const result = await preCheckUpload({
        fileName: item.file.name,
        size: item.file.size,
        parentId,
        relativePath: item.relativePath,
        partialHash: partialHash || undefined,
      })
      if (result.conflicts.length > 0) {
        const conflict = result.conflicts[0]
        const id = generateConflictId()
        conflicts.push({
          sourceId: id,
          sourceName: item.file.name,
          sourceType: 'file',
          existingId: conflict.existingId,
          existingName: conflict.existingName,
          existingType: conflict.existingType,
        })
        strategyByFile.set(item, defaultConflictStrategy ?? 'keep')
      }
    }

    if (conflicts.length > 0) {
      if (defaultConflictStrategy) {
        files.forEach((item) => {
          if (!strategyByFile.has(item)) {
            strategyByFile.set(item, defaultConflictStrategy)
          }
        })
      } else if (!openConflict) {
        conflicts.forEach((conflict) => {
          const item = findFileByName(files, conflict.sourceName)
          if (item) {
            strategyByFile.set(item, 'keep')
          }
        })
      } else {
        const chosen = await openConflict(conflicts)
        if (chosen === null) return
        conflicts.forEach((conflict) => {
          const item = findFileByName(files, conflict.sourceName)
          if (item) {
            strategyByFile.set(item, chosen[conflict.sourceId] ?? 'keep')
          }
        })
      }
    }

    await Promise.all(
      files.map(async (item) => {
        const strategy = strategyByFile.get(item)
        if (strategy === 'skip') return
        await upload(
          item.file,
          parentId,
          item.relativePath,
          onComplete,
          strategy ? async () => strategy : undefined,
        )
      }),
    )
  }

  async function preCheckSingle(
    item: UploadBatchFile,
    parentId: string,
    openConflict?: BatchConflictOpener,
    defaultConflictStrategy?: ConflictStrategy,
  ): Promise<ConflictStrategy | null | undefined> {
    const partialHash = await identityHash(item.file)
    const result = await preCheckUpload({
      fileName: item.file.name,
      size: item.file.size,
      parentId,
      relativePath: item.relativePath,
      partialHash: partialHash || undefined,
    })
    if (result.conflicts.length === 0) return undefined
    const conflict = result.conflicts[0]
    const id = generateConflictId()
    const wrapped: ConflictItemVo = {
      sourceId: id,
      sourceName: item.file.name,
      sourceType: 'file',
      existingId: conflict.existingId,
      existingName: conflict.existingName,
      existingType: conflict.existingType,
    }
    if (defaultConflictStrategy) {
      return defaultConflictStrategy
    }
    if (!openConflict) return 'keep'
    const chosen = await openConflict([wrapped])
    if (chosen === null) return null
    return chosen[id]
  }

  function findFileByName(files: UploadBatchFile[], name: string): UploadBatchFile | undefined {
    return files.find((item) => item.file.name === name)
  }

  return {
    uploadBatch,
  }
}
