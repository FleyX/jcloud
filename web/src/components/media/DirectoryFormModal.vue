<script setup lang="ts">
/**
 * 媒体库编辑弹窗
 * - 名称、媒体类型、定时重扫 cron、来源目录列表（多选，可增删）
 * - 编辑时媒体类型创建后不可修改，置灰只读
 */
import { computed, ref, watch } from 'vue'
import { X, FolderPlus, Folder } from '@lucide/vue'
import type { MediaDirectorySaveDto, MediaDirectoryVo, MediaType } from '@/types/media'
import DirectoryPickerModal, { type PickedDirectory } from '@/components/DirectoryPickerModal.vue'
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

interface SourceItem {
  fileNodeId: string
  folderName: string
}

const name = ref('')
const mediaType = ref<MediaType>('movie')
const scanCron = ref('')
const sources = ref<SourceItem[]>([])
const error = ref('')
const pickerOpen = ref(false)

watch(
  () => props.open,
  (open) => {
    if (!open) return
    error.value = ''
    pickerOpen.value = false
    if (props.editing) {
      name.value = props.editing.name
      mediaType.value = props.editing.mediaType
      scanCron.value = props.editing.scanCron ?? ''
      sources.value = props.editing.sources.map((source) => ({
        fileNodeId: source.fileNodeId,
        folderName: source.folderName,
      }))
    } else {
      name.value = ''
      mediaType.value = 'movie'
      scanCron.value = ''
      sources.value = []
    }
  },
)

const typeOptions: Array<{ value: MediaType; label: string }> = [
  { value: 'movie', label: '电影' },
  { value: 'tv', label: '电视' },
  { value: 'other', label: '其他' },
]

/** 编辑时媒体类型创建后不可修改 */
const typeReadonly = computed(() => !!props.editing)

/** 编辑时来源目录是否发生增删（会中断当前任务并强制全量重扫） */
const sourcesChanged = computed(() => {
  if (!props.editing) return false
  const before = props.editing.sources.map((source) => source.fileNodeId).sort()
  const after = sources.value.map((source) => source.fileNodeId).sort()
  return before.length !== after.length || before.some((id, index) => id !== after[index])
})

function addSources(nodes: PickedDirectory[]) {
  const existing = new Set(sources.value.map((source) => source.fileNodeId))
  for (const node of nodes) {
    if (!existing.has(node.id)) {
      sources.value.push({ fileNodeId: node.id, folderName: node.name })
      existing.add(node.id)
    }
  }
  if (!name.value.trim() && nodes[0]) {
    name.value = nodes[0].name
  }
  error.value = ''
}

function removeSource(fileNodeId: string) {
  sources.value = sources.value.filter((source) => source.fileNodeId !== fileNodeId)
}

function handleConfirm() {
  if (sources.value.length === 0) {
    error.value = '请至少选择一个来源目录'
    return
  }
  emit('confirm', {
    sourceFileNodeIds: sources.value.map((source) => source.fileNodeId),
    name: name.value.trim() || sources.value[0]?.folderName,
    mediaType: mediaType.value,
    scanCron: scanCron.value.trim() || undefined,
  })
}
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
          {{ editing ? '编辑媒体库' : '新增媒体库' }}
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
          名称
        </p>
        <input
          v-model="name"
          type="text"
          placeholder="默认取第一个来源目录名"
          class="mb-3 w-full rounded-xl border border-surface-200 bg-surface-50 px-4 py-2 text-sm outline-none focus:border-primary-300 focus:bg-white"
        >

        <p class="mb-1 text-xs font-medium text-surface-500">
          媒体类型
          <span
            v-if="typeReadonly"
            class="text-surface-400"
          >（创建后不可修改）</span>
        </p>
        <div class="mb-3 flex gap-2">
          <button
            v-for="option in typeOptions"
            :key="option.value"
            :disabled="typeReadonly"
            :class="
              cn(
                'rounded-xl px-4 py-1.5 text-sm transition-colors',
                mediaType === option.value
                  ? 'bg-primary-600 text-white'
                  : 'bg-surface-100 text-surface-600 hover:bg-surface-200',
                typeReadonly && 'cursor-not-allowed opacity-60 hover:bg-surface-100'
              )
            "
            @click="mediaType = option.value"
          >
            {{ option.label }}
          </button>
        </div>

        <p class="mb-1 text-xs font-medium text-surface-500">
          来源目录（至少 1 个）
        </p>
        <div class="mb-2 space-y-1.5">
          <p
            v-if="sources.length === 0"
            class="rounded-xl border border-dashed border-surface-200 py-3 text-center text-xs text-surface-400"
          >
            尚未选择来源目录
          </p>
          <div
            v-for="source in sources"
            :key="source.fileNodeId"
            class="flex items-center gap-2 rounded-xl border border-surface-100 bg-surface-50 px-3 py-2"
          >
            <Folder class="h-4 w-4 shrink-0 text-amber-400" />
            <span class="flex-1 truncate text-sm text-surface-700">{{ source.folderName }}</span>
            <button
              class="rounded-lg p-0.5 text-surface-400 hover:bg-surface-200 hover:text-red-500"
              title="移除"
              @click="removeSource(source.fileNodeId)"
            >
              <X class="h-3.5 w-3.5" />
            </button>
          </div>
        </div>
        <button
          class="mb-3 flex items-center gap-1.5 rounded-xl border border-primary-200 px-3 py-1.5 text-xs font-medium text-primary-600 hover:bg-primary-50"
          @click="pickerOpen = true"
        >
          <FolderPlus class="h-4 w-4" />
          添加来源目录
        </button>

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
          v-if="sourcesChanged"
          class="mb-2 text-xs text-amber-500"
        >
          增删来源目录将中断当前任务并强制全量重扫，被移除来源目录下的条目数据清空
        </p>

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
              sources.length > 0 ? 'bg-primary-600 hover:bg-primary-700' : 'bg-surface-300 cursor-not-allowed'
            )
          "
          :disabled="sources.length === 0"
          @click="handleConfirm"
        >
          保存
        </button>
      </div>
    </div>

    <DirectoryPickerModal
      :open="pickerOpen"
      :disabled-ids="sources.map((source) => source.fileNodeId)"
      @close="pickerOpen = false"
      @confirm="addSources"
    />
  </div>
</template>
