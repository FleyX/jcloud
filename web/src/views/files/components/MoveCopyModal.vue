<script setup lang="ts">
/**
 * 移动/复制弹窗。
 * 自包含：内部完成目标选择、预检、统一冲突解决、执行，最终通过 confirm 事件返回结果。
 */
import { computed, ref, watch } from 'vue'
import { X, FolderInput, Copy, Search } from '@lucide/vue'
import { cn } from '@/utils/cn'
import { copyFiles, moveFiles, preCheckOperation } from '@/api/file'
import { useTransferTaskStore } from '@/store/transferTask'
import FileConflictModal from '@/components/files/FileConflictModal.vue'
import FolderTree, { type TreeNode } from '@/components/files/FolderTree.vue'
import type { ConflictItemVo, ConflictStrategy, FileNodeVo, OperationResultVo } from '@/types/file'

interface Props {
  open: boolean
  type: 'move' | 'copy'
  files: FileNodeVo[]
  folders: FileNodeVo[]
}

const props = defineProps<Props>()
const emit = defineEmits<{
  close: []
  confirm: [results: OperationResultVo[]]
}>()

type Step = 'select' | 'result'

const targetParentId = ref<string>('0')
const targetNode = ref<TreeNode | null>(null)
const conflicts = ref<ConflictItemVo[]>([])
const strategies = ref<Record<string, ConflictStrategy>>({})
const results = ref<OperationResultVo[]>([])
const step = ref<Step>('select')
const loading = ref(false)
const conflictOpen = ref(false)
const transferTaskStore = useTransferTaskStore()

const isMove = computed(() => props.type === 'move')
const title = computed(() => (isMove.value ? '移动' : '复制'))
const icon = computed(() => (isMove.value ? FolderInput : Copy))
const keyword = ref('')
const disabledIds = computed(() => props.files.map((file) => file.id))

/** 源与目标是否跨来源（本地↔远程或跨远程挂载点） */
const isCrossSource = computed(() => {
  const source = props.files[0]
  if (!source) return false
  const sourceType = source.sourceType ?? 'local'
  const targetType = targetNode.value?.sourceType ?? 'local'
  if (sourceType !== targetType) return true
  return sourceType === 'remote' && source.remoteMountId !== targetNode.value?.remoteMountId
})

watch(
  () => props.open,
  (open) => {
    if (open) {
      step.value = 'select'
      targetParentId.value = '0'
      targetNode.value = null
      keyword.value = ''
      conflicts.value = []
      strategies.value = {}
      results.value = []
      loading.value = false
      conflictOpen.value = false
    }
  },
)

function handleClose() {
  emit('close')
}

function handleSelectTarget(node: TreeNode) {
  targetParentId.value = node.id
  targetNode.value = node
}

async function handleNext() {
  const items = props.files.map((file) => ({
    id: file.id,
    name: file.name,
  }))
  loading.value = true
  try {
    const conflictList = await preCheckOperation({
      type: props.type,
      targetParentId: targetParentId.value,
      items,
    })
    conflicts.value = conflictList
    strategies.value = {}
    if (conflictList.length > 0) {
      conflictOpen.value = true
    } else {
      await executeMoveCopy()
    }
  } finally {
    loading.value = false
  }
}

async function executeMoveCopy() {
  const items = props.files.map((file) => ({
    id: file.id,
    name: file.name,
    strategy: strategies.value[file.id],
  }))
  loading.value = true
  try {
    if (isCrossSource.value) {
      // 跨来源：创建异步传输任务，进度由传输任务浮层展示
      await transferTaskStore.create(props.type, {
        targetParentId: targetParentId.value,
        items: items.map((item) => ({ id: item.id, strategy: item.strategy })),
      })
      emit('confirm', [])
      emit('close')
      return
    }
    results.value = props.type === 'move'
      ? await moveFiles({ type: 'move', targetParentId: targetParentId.value, items })
      : await copyFiles({ type: 'copy', targetParentId: targetParentId.value, items })
    step.value = 'result'
  } finally {
    loading.value = false
  }
}

function handleConflictConfirm(chosen: Record<string, ConflictStrategy>) {
  strategies.value = chosen
  executeMoveCopy()
}

function handleConflictCancel() {
  conflictOpen.value = false
}

function handleFinish() {
  emit('confirm', results.value)
  emit('close')
}
</script>

<template>
  <div
    v-if="open"
    class="fixed inset-0 z-50 flex items-center justify-center bg-black/40 backdrop-blur-sm"
    @click.self="handleClose"
  >
    <div class="w-full max-w-md rounded-3xl border border-surface-200 bg-white p-6 shadow-soft">
      <div class="mb-4 flex items-center gap-3">
        <div class="flex h-10 w-10 items-center justify-center rounded-xl bg-primary-100 text-primary-600">
          <component
            :is="icon"
            class="h-5 w-5"
          />
        </div>
        <h3 class="text-lg font-semibold text-surface-900">
          {{ title }}
        </h3>
        <button
          class="ml-auto rounded-lg p-1 text-surface-400 hover:bg-surface-100"
          @click="handleClose"
        >
          <X class="h-4 w-4" />
        </button>
      </div>

      <!-- 选择目标 -->
      <div v-if="step === 'select'">
        <p class="mb-2 text-sm text-surface-500">
          选择目标位置
        </p>
        <div class="mb-3 flex items-center gap-2 rounded-xl border border-surface-200 bg-white px-3 py-2 shadow-sm">
          <Search class="h-4 w-4 text-surface-400" />
          <input
            v-model="keyword"
            type="text"
            placeholder="搜索文件夹..."
            class="flex-1 bg-transparent text-sm outline-none placeholder:text-surface-400"
          >
        </div>
        <div class="max-h-64 overflow-y-auto">
          <FolderTree
            :selected-id="targetParentId"
            :disabled-ids="disabledIds"
            :keyword="keyword"
            @select="handleSelectTarget"
          />
        </div>

        <div class="mt-5 flex justify-end gap-2">
          <button
            class="rounded-xl px-4 py-2 text-sm font-medium text-surface-600 transition-colors hover:bg-surface-100"
            @click="handleClose"
          >
            取消
          </button>
          <button
            :disabled="loading"
            class="rounded-xl bg-primary-600 px-4 py-2 text-sm font-semibold text-white shadow-soft transition-all hover:bg-primary-700 hover:shadow-card active:scale-95 disabled:opacity-60"
            @click="handleNext"
          >
            {{ loading ? '检测中...' : '下一步' }}
          </button>
        </div>
      </div>

      <!-- 结果 -->
      <div v-else-if="step === 'result'">
        <p class="mb-3 text-sm text-surface-500">
          操作结果
        </p>
        <div class="max-h-64 space-y-1 overflow-y-auto">
          <div
            v-for="result in results"
            :key="result.sourceId"
            class="flex items-center justify-between rounded-xl px-3 py-2 text-sm"
            :class="
              cn(
                result.status === 'success' && 'bg-emerald-50 text-emerald-700',
                result.status === 'skipped' && 'bg-amber-50 text-amber-700',
                result.status === 'failed' && 'bg-red-50 text-red-700'
              )
            "
          >
            <span>{{ result.sourceName }}</span>
            <span class="text-xs opacity-80">
              {{ result.status === 'success' ? (result.newName ? `已重命名为 ${result.newName}` : '成功') : result.status === 'skipped' ? '已跳过' : result.message }}
            </span>
          </div>
        </div>
        <div class="mt-5 flex justify-end">
          <button
            class="rounded-xl bg-primary-600 px-4 py-2 text-sm font-semibold text-white shadow-soft transition-all hover:bg-primary-700 hover:shadow-card active:scale-95"
            @click="handleFinish"
          >
            完成
          </button>
        </div>
      </div>
    </div>
  </div>

  <FileConflictModal
    v-model:open="conflictOpen"
    :title="`${title}冲突`"
    :confirm-text="`确认${title}`"
    :conflicts="conflicts"
    @confirm="handleConflictConfirm"
    @cancel="handleConflictCancel"
  />
</template>
