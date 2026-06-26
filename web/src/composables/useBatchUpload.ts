import { useChunkedUpload } from './useChunkedUpload'
import { preCheckUpload } from '@/api/file'
import { identityHash } from '@/utils/fileHash'
import type { ConflictItemVo, ConflictStrategy } from '@/types/file'

export type BatchConflictOpener = (conflicts: ConflictItemVo[]) => Promise<Record<string, ConflictStrategy> | null>

function generateConflictId(): string {
  return `conflict-${Date.now()}-${Math.random().toString(36).slice(2, 9)}`
}

/**
 * 批量上传组合式函数。
 *
 * 对选中的多个文件先统一进行冲突预检；若存在冲突则弹出批量冲突弹窗，
 * 支持每项单独选择 skip / overwrite / keep，也支持全部应用同一策略。
 * 文件夹不参与重名检测（未来支持文件夹上传时直接合并）。
 */
export function useBatchUpload() {
  const { upload } = useChunkedUpload()

  async function uploadBatch(
    files: File[],
    parentId = '0',
    onComplete?: () => void,
    openConflict?: BatchConflictOpener,
  ): Promise<void> {
    if (files.length === 0) return

    // 单文件沿用原有单条上传流程
    if (files.length === 1) {
      const file = files[0]
      const strategy = await preCheckSingle(file, parentId, openConflict)
      if (strategy === 'skip') return
      await upload(file, parentId, onComplete, strategy ? async () => strategy : undefined)
      return
    }

    const conflicts: ConflictItemVo[] = []
    const strategyByFileName: Record<string, ConflictStrategy> = {}

    for (const file of files) {
      const partialHash = await identityHash(file)
      const result = await preCheckUpload({
        fileName: file.name,
        size: file.size,
        parentId,
        partialHash: partialHash || undefined,
      })
      if (result.conflicts.length > 0) {
        const conflict = result.conflicts[0]
        const id = generateConflictId()
        conflicts.push({
          sourceId: id,
          sourceName: file.name,
          sourceType: 'file',
          existingId: conflict.existingId,
          existingName: conflict.existingName,
          existingType: conflict.existingType,
        })
        strategyByFileName[file.name] = 'keep'
      }
    }

    if (conflicts.length > 0) {
      if (!openConflict) {
        conflicts.forEach((conflict) => {
          strategyByFileName[conflict.sourceName] = 'keep'
        })
      } else {
        const chosen = await openConflict(conflicts)
        if (chosen === null) return
        conflicts.forEach((conflict) => {
          strategyByFileName[conflict.sourceName] = chosen[conflict.sourceId] ?? 'keep'
        })
      }
    }

    await Promise.all(
      files.map(async (file) => {
        const strategy = strategyByFileName[file.name]
        if (strategy === 'skip') return
        await upload(file, parentId, onComplete, strategy ? async () => strategy : undefined)
      }),
    )
  }

  async function preCheckSingle(
    file: File,
    parentId: string,
    openConflict?: BatchConflictOpener,
  ): Promise<ConflictStrategy | null | undefined> {
    const partialHash = await identityHash(file)
    const result = await preCheckUpload({
      fileName: file.name,
      size: file.size,
      parentId,
      partialHash: partialHash || undefined,
    })
    if (result.conflicts.length === 0) return undefined
    const conflict = result.conflicts[0]
    const id = generateConflictId()
    const wrapped: ConflictItemVo = {
      sourceId: id,
      sourceName: file.name,
      sourceType: 'file',
      existingId: conflict.existingId,
      existingName: conflict.existingName,
      existingType: conflict.existingType,
    }
    if (!openConflict) return 'keep'
    const chosen = await openConflict([wrapped])
    if (chosen === null) return null
    return chosen[id]
  }

  return {
    uploadBatch,
  }
}
