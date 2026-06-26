<script setup lang="ts">
/**
 * 移动/复制弹窗，含冲突解决。
 * 自包含：内部完成预检、冲突选择、执行，最终通过 confirm 事件返回结果。
 */
import { computed, ref, watch } from 'vue'
import { X, Folder, FolderInput, Copy, Check } from '@lucide/vue'
import { cn } from '@/utils/cn'
import { copyFiles, moveFiles, preCheckOperation } from '@/api/file'
import type { ConflictItemVo, ConflictStrategy, FileNodeVo, OperationResultVo } from '@/types/file'

function availableStrategies(conflict: ConflictItemVo): ConflictStrategy[] {
  return conflict.sourceType === 'folder'
    ? ['skip', 'overwrite']
    : ['skip', 'overwrite', 'auto_rename']
}

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

type Step = 'select' | 'conflict' | 'result'

const targetParentId = ref<string>('0')
const conflicts = ref<ConflictItemVo[]>([])
const strategies = ref<Record<string, ConflictStrategy>>({})
const results = ref<OperationResultVo[]>([])
const step = ref<Step>('select')
const loading = ref(false)

const isMove = computed(() => props.type === 'move')
const title = computed(() => (isMove.value ? '移动' : '复制'))
const icon = computed(() => (isMove.value ? FolderInput : Copy))

const targets = computed(() => [
  { id: '0', name: '根目录' },
  ...props.folders.map((folder) => ({ id: folder.id, name: folder.name })),
])

watch(
  () => props.open,
  (open) => {
    if (open) {
      step.value = 'select'
      targetParentId.value = '0'
      conflicts.value = []
      strategies.value = {}
      results.value = []
      loading.value = false
    }
  },
)

function handleClose() {
  emit('close')
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
    conflictList.forEach((conflict) => {
      strategies.value[conflict.sourceId] = conflict.sourceType === 'folder' ? 'skip' : 'auto_rename'
    })
    step.value = conflictList.length > 0 ? 'conflict' : 'result'
    if (conflictList.length === 0) {
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
    strategy: strategies.value[file.id] ?? 'overwrite',
  }))
  loading.value = true
  try {
    results.value = props.type === 'move'
      ? await moveFiles({ type: 'move', targetParentId: targetParentId.value, items })
      : await copyFiles({ type: 'copy', targetParentId: targetParentId.value, items })
    step.value = 'result'
  } finally {
    loading.value = false
  }
}

function handleConfirmFromConflict() {
  executeMoveCopy()
}

function applyAll(strategy: ConflictStrategy) {
  conflicts.value.forEach((conflict) => {
    if (availableStrategies(conflict).includes(strategy)) {
      strategies.value[conflict.sourceId] = strategy
    }
  })
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
        <div class="max-h-64 overflow-y-auto rounded-2xl border border-surface-200 bg-surface-50/50 p-1">
          <button
            v-for="target in targets"
            :key="target.id"
            :class="
              cn(
                'flex w-full items-center gap-2.5 rounded-xl px-3 py-2.5 text-sm transition-colors',
                targetParentId === target.id
                  ? 'bg-primary-50 text-primary-700'
                  : 'text-surface-700 hover:bg-surface-100'
              )
            "
            @click="targetParentId = target.id"
          >
            <Check
              v-if="targetParentId === target.id"
              class="h-4 w-4 text-primary-500"
            />
            <Folder
              v-else
              class="h-4 w-4 text-surface-400"
            />
            {{ target.name }}
          </button>
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

      <!-- 冲突解决 -->
      <div v-else-if="step === 'conflict'">
        <p class="mb-3 text-sm text-surface-500">
          检测到以下同名冲突，请选择处理方式
        </p>
        <div class="mb-4 flex gap-2">
          <button
            v-for="action in (['skip', 'overwrite', 'auto_rename'] as ConflictStrategy[])"
            :key="action"
            class="flex-1 rounded-lg border border-surface-200 bg-surface-50 px-2 py-1.5 text-xs font-medium text-surface-600 transition-colors hover:border-primary-300 hover:bg-primary-50 hover:text-primary-700"
            @click="applyAll(action)"
          >
            全部{{ action === 'skip' ? '跳过' : action === 'overwrite' ? '覆盖' : '重命名' }}
          </button>
        </div>

        <div class="max-h-64 space-y-2 overflow-y-auto">
          <div
            v-for="conflict in conflicts"
            :key="conflict.sourceId"
            class="rounded-2xl border border-surface-200 bg-surface-50/50 p-3"
          >
            <p class="mb-2 text-sm font-medium text-surface-800">
              {{ conflict.sourceName }}
            </p>
            <div :class="cn('grid gap-2', conflict.sourceType === 'folder' ? 'grid-cols-2' : 'grid-cols-3')">
              <button
                v-for="option in (
                  conflict.sourceType === 'folder'
                    ? [
                      { code: 'skip', label: '跳过' },
                      { code: 'overwrite', label: '覆盖' },
                    ]
                    : [
                      { code: 'skip', label: '跳过' },
                      { code: 'overwrite', label: '覆盖' },
                      { code: 'auto_rename', label: '自动重命名' },
                    ]
                ) as { code: ConflictStrategy; label: string }[]"
                :key="option.code"
                :class="
                  cn(
                    'rounded-lg px-2 py-1.5 text-xs font-medium transition-colors',
                    strategies[conflict.sourceId] === option.code
                      ? 'bg-primary-600 text-white'
                      : 'bg-white text-surface-600 hover:bg-surface-100'
                  )
                "
                @click="strategies[conflict.sourceId] = option.code"
              >
                {{ option.label }}
              </button>
            </div>
          </div>
        </div>

        <div class="mt-5 flex justify-end gap-2">
          <button
            class="rounded-xl px-4 py-2 text-sm font-medium text-surface-600 transition-colors hover:bg-surface-100"
            @click="step = 'select'"
          >
            上一步
          </button>
          <button
            :disabled="loading"
            class="rounded-xl bg-primary-600 px-4 py-2 text-sm font-semibold text-white shadow-soft transition-all hover:bg-primary-700 hover:shadow-card active:scale-95 disabled:opacity-60"
            @click="handleConfirmFromConflict"
          >
            {{ loading ? '执行中...' : `确认${title}` }}
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
</template>
