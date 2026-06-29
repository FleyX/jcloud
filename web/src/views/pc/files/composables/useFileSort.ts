import { ref } from 'vue'
import type { FileSortField, FileSortOrder } from '@/types/file'

/**
 * 文件列表排序状态与切换逻辑
 */
export function useFileSort(defaultField: FileSortField = 'createTime', defaultOrder: FileSortOrder = 'desc') {
  const sortField = ref<FileSortField>(defaultField)
  const sortOrder = ref<FileSortOrder>(defaultOrder)

  function toggleSort(field: FileSortField, onChange: () => void) {
    if (sortField.value === field) {
      sortOrder.value = sortOrder.value === 'asc' ? 'desc' : 'asc'
    } else {
      sortField.value = field
      sortOrder.value = 'asc'
    }
    onChange()
  }

  return {
    sortField,
    sortOrder,
    toggleSort,
  }
}
