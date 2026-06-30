/**
 * 文件节点视图对象
 */
export interface FileNodeVo {
  id: string
  userId: string
  parentId: string
  name: string
  type: 'file' | 'folder'
  size: string
  storageSpaceId: string
  pathName: string
  mimeType?: string
  hash?: string
  status: number
  physicalPath?: string
  createTime?: string
  updateTime?: string
}

/**
 * 文件排序字段
 */
export type FileSortField = 'name' | 'size' | 'createTime'

/**
 * 文件排序方向
 */
export type FileSortOrder = 'asc' | 'desc'

/**
 * 文件分页查询参数
 */
export interface FilePageQuery {
  parentId?: string
  name?: string
  sortField?: FileSortField
  sortOrder?: FileSortOrder
  pageNum?: number
  pageSize?: number
}

/**
 * 冲突解决策略
 */
export type ConflictStrategy = 'skip' | 'overwrite' | 'keep'

/**
 * 操作类型
 */
export type OperationType = 'move' | 'copy'

/**
 * 冲突项
 */
export interface ConflictItemVo {
  sourceId: string
  sourceName: string
  sourceType: 'file' | 'folder'
  existingId: string
  existingName: string
  existingType: 'file' | 'folder'
  nodeId?: string
  sourcePath?: string
  targetPath?: string
  type?: 'file' | 'folder'
  suggestedStrategy?: ConflictStrategy
  autoMerge?: boolean
}

/**
 * 操作项
 */
export interface OperationItem {
  id: string
  name: string
  strategy?: ConflictStrategy
  newName?: string
}

/**
 * 操作结果
 */
export interface OperationResultVo {
  sourceId: string
  sourceName: string
  status: 'success' | 'skipped' | 'failed'
  newName?: string
  message?: string
  nodeId?: string
}

/**
 * 重命名参数
 */
export interface FileRenameRequest {
  id: string
  newName: string
}

/**
 * 创建文件夹参数
 */
export interface FileCreateFolderRequest {
  parentId?: string
  name: string
}

/**
 * 移动/复制预检参数
 */
export interface FilePreCheckOperationRequest {
  type: OperationType
  targetParentId: string
  items: OperationItem[]
}

/**
 * 移动/复制执行参数
 */
export interface FileExecuteOperationRequest {
  type: OperationType
  targetParentId: string
  items: OperationItem[]
  globalStrategy?: ConflictStrategy
}

/**
 * 回收站记录
 */
export interface RecycleRecordVo {
  id: string
  name: string
  type: 'file' | 'folder'
  originalPathName: string
  totalSize: string
  createTime: string
}

/**
 * 删除到回收站参数
 */
export interface FileDeleteRequest {
  ids: string[]
}

/**
 * 恢复项
 */
export interface RestoreItem {
  id: string
  strategy?: ConflictStrategy
}

/**
 * 恢复执行参数
 */
export interface FileExecuteRestoreRequest {
  items: RestoreItem[]
  globalStrategy?: ConflictStrategy
}

/**
 * 恢复前冲突预检参数
 */
export interface FilePreCheckRestoreRequest {
  ids: string[]
}

/**
 * 永久删除回收站记录参数
 */
export interface FilePermanentDeleteRequest {
  ids: string[]
}



/**
 * 上传前预检参数
 */
export interface FileUploadPreCheckRequest {
  clientFileId: string
  fileName: string
  size: number
  parentId?: string
  relativePath?: string
  partialHash?: string
}

/**
 * 批量上传前预检请求
 */
export interface BatchUploadPreCheckRequest {
  items: FileUploadPreCheckRequest[]
}

/**
 * 秒传参数
 */
export interface FileInstantUploadRequest {
  candidateId: string
  fullHash: string
  fileName: string
  parentId?: string
  relativePath?: string
  strategy?: ConflictStrategy
}

/**
 * 分片上传初始化参数
 */
export interface ChunkedUploadInitRequest {
  clientFileId: string
  fileName: string
  size: number
  parentId?: string
  relativePath?: string
}

/**
 * 批量分片上传初始化请求
 */
export interface BatchChunkedUploadInitRequest {
  items: ChunkedUploadInitRequest[]
}

/**
 * 上传前预检结果
 */
export interface UploadPreCheckResult {
  conflicts: ConflictItemVo[]
  candidates: FileNodeVo[]
}

/**
 * 批量上传前预检响应项状态
 */
export type BatchUploadPreCheckItemStatus = 'success' | 'error'

/**
 * 批量上传前预检响应项
 */
export interface BatchUploadPreCheckItem {
  clientFileId: string
  status: BatchUploadPreCheckItemStatus
  errorCode?: string
  errorMessage?: string
  data?: UploadPreCheckResult
}

/**
 * 批量分片上传初始化响应项状态
 */
export type BatchChunkedUploadInitItemStatus = 'success' | 'error'

/**
 * 分片上传初始化响应。
 */
export interface ChunkedUploadInitResponse {
  uploadId: string
  chunkSize: number
  totalChunks: number
}

/**
 * 批量分片上传初始化响应项
 */
export interface BatchChunkedUploadInitItem {
  clientFileId: string
  status: BatchChunkedUploadInitItemStatus
  errorCode?: string
  errorMessage?: string
  data?: ChunkedUploadInitResponse
}

/**
 * 批量下载请求
 */
export interface FileBatchDownloadRequest {
  ids: string[]
}

/**
 * 批量下载任务
 */
export interface FileZipTaskVo {
  taskId: string
  status: 'pending' | 'running' | 'completed' | 'failed'
  totalBytes?: string
  message?: string
}
