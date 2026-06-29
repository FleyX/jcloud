import { ref } from 'vue'
import { createFolder, renameFile } from '@/api/file'
import { useNotificationStore } from '@/store/notification'
import type { FileNodeVo } from '@/types/file'

export function useFileOperations(loadFiles: () => Promise<void>) {
  const notificationStore = useNotificationStore()

  const createFolderOpen = ref(false)
  const renameOpen = ref(false)
  const renameTarget = ref<FileNodeVo | undefined>(undefined)

  function openCreateFolder() {
    createFolderOpen.value = true
  }

  async function handleCreateFolder(name: string, parentId: string) {
    try {
      await createFolder({ parentId, name })
      createFolderOpen.value = false
      notificationStore.success('文件夹创建成功')
      await loadFiles()
    } catch {
      // 请求层已统一提示
    }
  }

  function openRename(file: FileNodeVo) {
    renameTarget.value = file
    renameOpen.value = true
  }

  async function handleRename(id: string, newName: string) {
    try {
      await renameFile({ id, newName })
      renameOpen.value = false
      notificationStore.success('重命名成功')
      await loadFiles()
    } catch {
      // 请求层已统一提示
    }
  }

  return {
    createFolderOpen,
    renameOpen,
    renameTarget,
    openCreateFolder,
    handleCreateFolder,
    openRename,
    handleRename,
  }
}
