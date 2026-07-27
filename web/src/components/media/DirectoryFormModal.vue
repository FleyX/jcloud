<script setup lang="ts">
/**
 * 视频目录编辑弹窗
 * - 选择虚拟文件树文件夹（面包屑逐级浏览）
 * - 指定媒体类型与定时重扫 cron
 */
import { ref, watch } from 'vue'
import { X, Folder, ChevronRight } from '@lucide/vue'
import type { MediaDirectorySaveDto, MediaDirectoryVo, MediaType } from '@/types/media'
import { fetchChildFolders } from '@/api/file'
import type { FileNodeVo } from '@/types/file'
import { cn } from '@/utils/cn'

interface Props {
  open: boolean
  editing?: MediaDirectoryVo | null
}

const props = defineProps<Props>()
const emit = defineEmits<{
  close: []
  confirm: [dto: MediaDirectorySaveDto]
}>()

const ROOT_ID = '0000000000000'

const name = ref('')
const mediaType = ref<MediaType>('movie')
const scanCron = ref('')
const selectedFolderId = ref('')
const selectedFolderName = ref('')
const error = ref('')

const folderStack = ref<FileNodeVo[]>([])
const childFolders = ref<FileNodeVo[]>([])

watch(
  () => props.open,
  async (open) => {
    if (!open) return
    error.value = ''
    folderStack.value = []
    if (props.editing) {
      name.value = props.editing.name
      mediaType.value = props.editing.mediaType
      scanCron.value = props.editing.scanCron ?? ''
      selectedFolderId.value = props.editing.fileNodeId
      selectedFolderName.value = props.editing.name
    } else {
      name.value = ''
      mediaType.value = 'movie'
      scanCron.value = ''
      selectedFolderId.value = ''
      selectedFolderName.value = ''
    }
    await loadFolders(ROOT_ID)
  },
)

async function loadFolders(parentId: string) {
  childFolders.value = await fetchChildFolders(parentId)
}

function enterFolder(folder: FileNodeVo) {
  folderStack.value.push(folder)
  loadFolders(folder.id)
}

function backTo(index: number) {
  folderStack.value = folderStack.value.slice(0, index + 1)
  const target = index < 0 ? ROOT_ID : folderStack.value[index].id
  loadFolders(target)
}

function selectCurrentFolder() {
  const current = folderStack.value[folderStack.value.length - 1]
  if (!current) {
    error.value = '不能选择根目录，请进入一个文件夹'
    return
  }
  selectedFolderId.value = current.id
  selectedFolderName.value = current.name
  if (!name.value.trim()) {
    name.value = current.name
  }
  error.value = ''
}

function handleConfirm() {
  if (!selectedFolderId.value) {
    error.value = '请选择视频文件夹'
    return
  }
  emit('confirm', {
    fileNodeId: selectedFolderId.value,
    name: name.value.trim() || undefined,
    mediaType: mediaType.value,
    scanCron: scanCron.value.trim() || undefined,
  })
}

const typeOptions: Array<{ value: MediaType; label: string }> = [
  { value: 'movie', label: '电影' },
  { value: 'tv', label: '电视' },
  { value: 'other', label: '其他' },
]
</script>

<template>
  <div
    v-if="open"
    class="fixed inset-0 z-50 flex items-center justify-center bg-black/40 backdrop-blur-sm"
    @click.self="emit('close')"
  >
    <div class="flex max-h-[85vh] w-full max-w-md flex-col rounded-3xl border border-surface-200 bg-white p-6 shadow-soft">
      <div class="mb-4 flex items-center gap-3">
        <h3 class="text-lg font-semibold text-surface-900">
          {{ editing ? '编辑视频目录' : '新增视频目录' }}
        </h3>
        <button
          class="ml-auto rounded-lg p-1 text-surface-400 hover:bg-surface-100"
          @click="emit('close')"
        >
          <X class="h-4 w-4" />
        </button>
      </div>

      <div class="flex-1 overflow-y-auto">
        <p class="mb-1 text-xs font-medium text-surface-500">
          选择文件夹
        </p>
        <div class="mb-2 flex items-center gap-1 text-sm text-surface-600">
          <button
            class="hover:text-primary-600"
            @click="backTo(-1)"
          >
            根目录
          </button>
          <template
            v-for="(folder, index) in folderStack"
            :key="folder.id"
          >
            <ChevronRight class="h-3 w-3 text-surface-300" />
            <button
              class="max-w-24 truncate hover:text-primary-600"
              @click="backTo(index)"
            >
              {{ folder.name }}
            </button>
          </template>
        </div>
        <div class="mb-3 max-h-40 overflow-y-auto rounded-2xl border border-surface-100">
          <p
            v-if="childFolders.length === 0"
            class="py-4 text-center text-xs text-surface-400"
          >
            无子文件夹
          </p>
          <button
            v-for="folder in childFolders"
            :key="folder.id"
            class="flex w-full items-center gap-2 px-3 py-2 text-left text-sm text-surface-700 hover:bg-surface-50"
            @click="enterFolder(folder)"
          >
            <Folder class="h-4 w-4 text-amber-400" />
            <span class="truncate">{{ folder.name }}</span>
          </button>
        </div>
        <div class="mb-3 flex items-center gap-2">
          <button
            class="rounded-xl border border-primary-200 px-3 py-1.5 text-xs font-medium text-primary-600 hover:bg-primary-50"
            @click="selectCurrentFolder"
          >
            选择当前文件夹
          </button>
          <span
            v-if="selectedFolderName"
            class="truncate text-xs text-surface-500"
          >
            已选：{{ selectedFolderName }}
          </span>
        </div>

        <p class="mb-1 text-xs font-medium text-surface-500">
          显示名
        </p>
        <input
          v-model="name"
          type="text"
          placeholder="默认取文件夹名"
          class="mb-3 w-full rounded-xl border border-surface-200 bg-surface-50 px-4 py-2 text-sm outline-none focus:border-primary-300 focus:bg-white"
        >

        <p class="mb-1 text-xs font-medium text-surface-500">
          媒体类型
        </p>
        <div class="mb-3 flex gap-2">
          <button
            v-for="option in typeOptions"
            :key="option.value"
            :class="
              cn(
                'rounded-xl px-4 py-1.5 text-sm transition-colors',
                mediaType === option.value
                  ? 'bg-primary-600 text-white'
                  : 'bg-surface-100 text-surface-600 hover:bg-surface-200'
              )
            "
            @click="mediaType = option.value"
          >
            {{ option.label }}
          </button>
        </div>

        <p class="mb-1 text-xs font-medium text-surface-500">
          定时重扫 cron（可选）
        </p>
        <input
          v-model="scanCron"
          type="text"
          placeholder="如 0 0 3 * * *（每天凌晨 3 点）"
          class="mb-2 w-full rounded-xl border border-surface-200 bg-surface-50 px-4 py-2 text-sm outline-none focus:border-primary-300 focus:bg-white"
        >

        <p
          v-if="error"
          class="mb-2 text-xs text-red-500"
        >
          {{ error }}
        </p>
      </div>

      <div class="mt-2 flex justify-end gap-2">
        <button
          class="rounded-xl px-4 py-2 text-sm font-medium text-surface-600 hover:bg-surface-100"
          @click="emit('close')"
        >
          取消
        </button>
        <button
          :class="
            cn(
              'rounded-xl px-4 py-2 text-sm font-semibold text-white',
              selectedFolderId ? 'bg-primary-600 hover:bg-primary-700' : 'bg-surface-300 cursor-not-allowed'
            )
          "
          :disabled="!selectedFolderId"
          @click="handleConfirm"
        >
          保存
        </button>
      </div>
    </div>
  </div>
</template>
