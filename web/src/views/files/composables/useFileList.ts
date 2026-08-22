import { computed, ref, watch } from 'vue'
import {
  deleteToTrash,
  downloadBatchFiles,
  fetchFilePage,
} from '@/api/file'
import { createShare, updateShare } from '@/api/share'
import { useBatchUpload } from '@/composables/useBatchUpload'
import { useConfirmStore } from '@/store/confirm'
import { useNotificationStore } from '@/store/notification'
import { useTransferStore } from '@/store/transfer'
import { useTransferTaskStore } from '@/store/transferTask'
import { useUserStore } from '@/store/user'
import { formatSize, inferFileType } from '@/utils/fileDisplay'
import type { FileDisplayType } from '@/utils/fileDisplay'
import type {
  ConflictItemVo,
  ConflictStrategy,
  FileNodeVo,
  FileSortField,
  FileSortOrder,
  OperationResultVo,
} from '@/types/file'
import type { ShareCreateRequest, ShareDetailVo, ShareUpdateRequest } from '@/types/share'

export interface DisplayFileNode extends FileNodeVo {
  iconType: FileDisplayType
  displaySize: string
  selected: boolean
}

export interface UseFileListOptions {
  rootName?: string
  initialParentId?: string
}

/** 文件列表排序偏好存储 key（全局单一 key，值结构为 {sortField, sortOrder}） */
const FILE_LIST_SORT_KEY = 'file-list-sort'
/** 合法排序字段集合 */
const SORT_FIELDS: readonly FileSortField[] = ['name', 'size', 'createTime']
/** 合法排序方向集合 */
const SORT_ORDERS: readonly FileSortOrder[] = ['asc', 'desc']

function hasMixedSource(nodes: FileNodeVo[]): boolean {
  if (nodes.length < 2) return false
  const firstSource = nodes[0].sourceType || 'local'
  const firstMountId = nodes[0].remoteMountId
  return nodes.some((node) => {
    const source = node.sourceType || 'local'
    if (source !== firstSource) return true
    if (source === 'remote' && node.remoteMountId !== firstMountId) return true
    return false
  })
}

/**
 * 文件列表页面级状态与行为组合式函数。
 * 抽象 PC / 移动端全部文件页共用逻辑：加载、搜索、排序、选择、上传冲突、
 * 预览、分享、移动/复制、删除、批量下载。
 */
export function useFileList(options: UseFileListOptions = {}) {
  const { rootName = '全部文件', initialParentId = '0' } = options

  const notificationStore = useNotificationStore()
  const confirmStore = useConfirmStore()
  const transferStore = useTransferStore()
  const userStore = useUserStore()
  const { uploadBatch } = useBatchUpload()

  const files = ref<FileNodeVo[]>([])
  const loading = ref(false)
  const keyword = ref('')
  const selectedIds = ref<Set<string>>(new Set())
  const currentParentId = ref(initialParentId)
  const breadcrumbStack = ref<Array<{ id: string; name: string }>>([
    { id: initialParentId, name: rootName },
  ])

  const sorted = restoreSort()
  const sortField = ref<FileSortField>(sorted.field)
  const sortOrder = ref<FileSortOrder>(sorted.order)

  const previewOpen = ref(false)
  const previewTarget = ref<FileNodeVo | null>(null)

  const moveCopyOpen = ref(false)
  const moveCopyType = ref<'move' | 'copy'>('move')
  const moveCopyTargets = ref<FileNodeVo[]>([])

  const shareOpen = ref(false)
  const shareEditTarget = ref<ShareDetailVo | undefined>(undefined)

  const uploadConflictOpen = ref(false)
  const uploadConflicts = ref<ConflictItemVo[]>([])
  let uploadConflictResolve: ((strategies: Record<string, ConflictStrategy> | null) => void) | null = null

  const folders = computed(() => files.value.filter((file) => file.type === 'folder'))
  const selectedFiles = computed(() => files.value.filter((file) => selectedIds.value.has(file.id)))
  const isAllSelected = computed(() => files.value.length > 0 && selectedIds.value.size === files.value.length)
  const hasMixedSelection = computed(() => hasMixedSource(selectedFiles.value))
  const isSearching = computed(() => keyword.value.trim().length > 0)

  const displayFiles = computed<DisplayFileNode[]>(() =>
    files.value.map((file) => ({
      ...file,
      iconType: inferFileType(file),
      displaySize: formatSize(file.size),
      selected: selectedIds.value.has(file.id),
    })),
  )

  const transferTaskStore = useTransferTaskStore()
  // 跨来源传输任务到达终态后刷新文件列表
  watch(() => transferTaskStore.finishedTick, () => {
    loadFiles()
  })

  /**
   * 从 localStorage 恢复排序偏好，逐项校验字段与方向，任一非法或异常即回退默认。
   */
  function restoreSort(): { field: FileSortField; order: FileSortOrder } {
    try {
      const saved = JSON.parse(localStorage.getItem(FILE_LIST_SORT_KEY) || 'null') as {
        sortField?: FileSortField
        sortOrder?: FileSortOrder
      } | null
      const field = saved?.sortField
      const order = saved?.sortOrder
      return {
        field: SORT_FIELDS.includes(field as FileSortField) ? (field as FileSortField) : 'createTime',
        order: SORT_ORDERS.includes(order as FileSortOrder) ? (order as FileSortOrder) : 'desc',
      }
    } catch {
      return { field: 'createTime', order: 'desc' }
    }
  }

  function persistSort() {
    localStorage.setItem(
      FILE_LIST_SORT_KEY,
      JSON.stringify({ sortField: sortField.value, sortOrder: sortOrder.value }),
    )
  }

  async function loadFiles() {
    loading.value = true
    try {
      const res = await fetchFilePage({
        parentId: currentParentId.value,
        name: keyword.value,
        sortField: sortField.value,
        sortOrder: sortOrder.value,
        pageNum: 1,
        pageSize: 100,
      })
      files.value = res.records
      selectedIds.value.clear()
    } finally {
      loading.value = false
    }
  }

  function setSortField(field: FileSortField) {
    sortField.value = field
    persistSort()
    return loadFiles()
  }

  function toggleSortOrder() {
    sortOrder.value = sortOrder.value === 'asc' ? 'desc' : 'asc'
    persistSort()
    return loadFiles()
  }

  /**
   * PC 端表头排序交互：
   * 同字段切换方向，不同字段默认升序。
   */
  function toggleSort(field: FileSortField) {
    if (sortField.value === field) {
      sortOrder.value = sortOrder.value === 'asc' ? 'desc' : 'asc'
    } else {
      sortField.value = field
      sortOrder.value = 'asc'
    }
    persistSort()
    return loadFiles()
  }

  /**
   * 同时设置排序字段与方向，仅触发一次列表加载。
   * 供排序弹窗确认回调等一次决定字段与方向的场景使用。
   */
  function setSort(field: FileSortField, order: FileSortOrder) {
    sortField.value = field
    sortOrder.value = order
    persistSort()
    return loadFiles()
  }

  function handleSearch() {
    return loadFiles()
  }

  // 搜索关键词实时过滤：输入防抖 300ms 后自动查询
  let searchTimer: ReturnType<typeof setTimeout> | null = null
  let suppressKeywordWatch = false
  watch(keyword, () => {
    if (suppressKeywordWatch) {
      suppressKeywordWatch = false
      return
    }
    if (searchTimer) clearTimeout(searchTimer)
    searchTimer = setTimeout(() => {
      void loadFiles()
    }, 300)
  })

  /** 程序化清空关键词（随后会显式调用 loadFiles），避免触发重复查询 */
  function resetKeywordSilently() {
    if (keyword.value !== '') suppressKeywordWatch = true
    keyword.value = ''
  }

  function clearSearch() {
    resetKeywordSilently()
    return loadFiles()
  }

  function enterFolder(file: FileNodeVo) {
    if (file.type !== 'folder') return
    currentParentId.value = file.id
    breadcrumbStack.value.push({ id: file.id, name: file.name })
    resetKeywordSilently()
    return loadFiles()
  }

  function navigateToBreadcrumb(index: number) {
    breadcrumbStack.value = breadcrumbStack.value.slice(0, index + 1)
    currentParentId.value = breadcrumbStack.value[index].id
    resetKeywordSilently()
    return loadFiles()
  }

  function toggleSelect(id: string) {
    if (selectedIds.value.has(id)) {
      selectedIds.value.delete(id)
    } else {
      selectedIds.value.add(id)
    }
  }

  function toggleSelectAll() {
    if (isAllSelected.value) {
      selectedIds.value.clear()
    } else {
      selectedIds.value = new Set(files.value.map((file) => file.id))
    }
  }

  function clearSelection() {
    selectedIds.value.clear()
  }

  function openPreview(file: FileNodeVo) {
    if (file.type !== 'file') return
    previewTarget.value = file
    previewOpen.value = true
  }

  function openMoveCopy(type: 'move' | 'copy', targets: FileNodeVo[]) {
    moveCopyType.value = type
    moveCopyTargets.value = targets
    moveCopyOpen.value = true
  }

  async function handleMoveCopyResult(results: OperationResultVo[]) {
    const successCount = results.filter((r) => r.status === 'success').length
    if (successCount > 0) {
      await loadFiles()
    }
  }

  async function handleDelete(file: FileNodeVo) {
    const confirmed = await confirmStore.open({
      title: '删除文件',
      message: `确定将 "${file.name}" 移动到回收站吗？`,
      confirmText: '删除',
      type: 'danger',
    })
    if (!confirmed) return
    await deleteToTrash({ ids: [file.id] })
    // 删除占用已用空间，防抖刷新用户容量信息
    userStore.scheduleUserInfoRefresh()
    notificationStore.success('已移动到回收站')
    await loadFiles()
  }

  async function handleBatchDelete() {
    const targets = selectedFiles.value
    if (targets.length === 0) return
    const confirmed = await confirmStore.open({
      title: '批量删除',
      message: `确定将选中的 ${targets.length} 项移动到回收站吗？`,
      confirmText: '删除',
      type: 'danger',
    })
    if (!confirmed) return
    await deleteToTrash({ ids: targets.map((f) => f.id) })
    // 删除占用已用空间，防抖刷新用户容量信息
    userStore.scheduleUserInfoRefresh()
    notificationStore.success('已移动到回收站')
    await loadFiles()
  }

  async function handleBatchDownload() {
    const targets = selectedFiles.value
    if (targets.length === 0) return
    const taskId = `dl-${Date.now()}`
    transferStore.addDownloadTask({ taskId, fileName: 'archive.zip' })
    try {
      await downloadBatchFiles(
        targets.map((f) => f.id),
        'archive.zip',
        (progress) => transferStore.updateDownloadProgress(taskId, progress),
      )
      transferStore.completeDownloadTask(taskId)
      notificationStore.success('下载完成')
    } catch (err) {
      const message = err instanceof Error ? err.message : '下载失败'
      transferStore.failDownloadTask(taskId, message)
      // 请求层已统一提示
    }
  }

  function openShareModal() {
    const targets = selectedFiles.value
    if (targets.length === 0) return
    if (hasMixedSource(targets)) {
      notificationStore.error('分享不能同时包含本地与远程文件')
      return
    }
    shareEditTarget.value = undefined
    shareOpen.value = true
  }

  async function handleCreateShare(payload: ShareCreateRequest) {
    const share = await createShare(payload)
    shareOpen.value = false
    selectedIds.value.clear()
    notificationStore.success('分享创建成功')
    return share
  }

  async function handleUpdateShare(payload: ShareUpdateRequest) {
    if (!shareEditTarget.value) return
    await updateShare(shareEditTarget.value.id, payload)
    shareOpen.value = false
    notificationStore.success('分享已更新')
  }

  function handleShareConfirm(payload: ShareCreateRequest | ShareUpdateRequest) {
    if (shareEditTarget.value) {
      return handleUpdateShare(payload as ShareUpdateRequest)
    }
    return handleCreateShare(payload as ShareCreateRequest)
  }

  function openUploadConflict(conflicts: ConflictItemVo[]): Promise<Record<string, ConflictStrategy> | null> {
    return new Promise((resolve) => {
      uploadConflicts.value = conflicts
      uploadConflictResolve = resolve
      uploadConflictOpen.value = true
    })
  }

  function handleUploadConflictConfirm(strategies: Record<string, ConflictStrategy>) {
    uploadConflictOpen.value = false
    uploadConflictResolve?.(strategies)
    uploadConflictResolve = null
  }

  function handleUploadConflictCancel() {
    uploadConflictOpen.value = false
    uploadConflictResolve?.(null)
    uploadConflictResolve = null
  }

  async function uploadFiles(rawFiles: File[]) {
    if (rawFiles.length === 0) return
    const batchFiles = rawFiles.map((file) => ({ file }))
    await uploadBatch(batchFiles, currentParentId.value, {
      onComplete: loadFiles,
      openConflict: openUploadConflict,
    })
  }

  async function uploadFolder(rawFiles: File[]) {
    if (rawFiles.length === 0) return
    const batchFiles = rawFiles.map((file) => ({
      file,
      relativePath: file.webkitRelativePath || file.name,
    }))
    await uploadBatch(batchFiles, currentParentId.value, {
      onComplete: loadFiles,
      defaultConflictStrategy: 'keep',
    })
  }

  return {
    files,
    loading,
    keyword,
    currentParentId,
    breadcrumbStack,
    sortField,
    sortOrder,
    selectedIds,
    previewOpen,
    previewTarget,
    moveCopyOpen,
    moveCopyType,
    moveCopyTargets,
    shareOpen,
    shareEditTarget,
    uploadConflictOpen,
    uploadConflicts,

    folders,
    selectedFiles,
    isAllSelected,
    hasMixedSelection,
    isSearching,
    displayFiles,

    loadFiles,
    setSortField,
    toggleSortOrder,
    toggleSort,
    setSort,
    handleSearch,
    clearSearch,
    enterFolder,
    navigateToBreadcrumb,
    toggleSelect,
    toggleSelectAll,
    clearSelection,
    openPreview,
    openMoveCopy,
    handleMoveCopyResult,
    handleDelete,
    handleBatchDelete,
    handleBatchDownload,
    openShareModal,
    handleCreateShare,
    handleUpdateShare,
    handleShareConfirm,
    openUploadConflict,
    handleUploadConflictConfirm,
    handleUploadConflictCancel,
    uploadFiles,
    uploadFolder,
  }
}
