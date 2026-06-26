<script setup lang="ts">
/**
 * 统一文件冲突解决弹窗。
 * 用于上传、移动、复制、恢复等场景下，为每个文件冲突项选择 skip / overwrite / keep。
 * 文件夹冲突统一按递归合并处理，不在列表中展示策略选择。
 */
import { computed, ref, watch } from 'vue'
import {
  DialogRoot,
  DialogPortal,
  DialogOverlay,
  DialogContent,
  DialogTitle,
  DialogDescription,
} from 'radix-vue'
import { X, FileWarning } from '@lucide/vue'
import { cn } from '@/utils/cn'
import type { ConflictItemVo, ConflictStrategy } from '@/types/file'

interface Props {
  open: boolean
  conflicts: ConflictItemVo[]
  title?: string
  confirmText?: string
}

const props = withDefaults(defineProps<Props>(), {
  title: '文件冲突',
  confirmText: '确认',
})

const emit = defineEmits<{
  'update:open': [value: boolean]
  confirm: [strategies: Record<string, ConflictStrategy>]
  cancel: []
}>()

const strategies = ref<Record<string, ConflictStrategy>>({})

const fileConflicts = computed(() => props.conflicts.filter((c) => c.autoMerge !== true))
const folderMergeCount = computed(() => props.conflicts.filter((c) => c.autoMerge === true).length)
const allResolved = computed(() => fileConflicts.value.every((c) => strategies.value[c.sourceId] !== undefined))

watch(
  () => props.conflicts,
  (conflicts) => {
    strategies.value = {}
    conflicts.forEach((conflict) => {
      strategies.value[conflict.sourceId] = 'keep'
    })
  },
  { immediate: true },
)

function close() {
  emit('update:open', false)
}

function handleCancel() {
  close()
  emit('cancel')
}

function handleConfirm() {
  if (!allResolved.value) return
  close()
  emit('confirm', { ...strategies.value })
}

function setStrategy(sourceId: string, strategy: ConflictStrategy) {
  strategies.value[sourceId] = strategy
}

function applyAll(strategy: ConflictStrategy) {
  fileConflicts.value.forEach((conflict) => {
    strategies.value[conflict.sourceId] = strategy
  })
}

function formatStrategyLabel(strategy: ConflictStrategy): string {
  return strategy === 'skip' ? '跳过' : strategy === 'overwrite' ? '覆盖' : '保留'
}
</script>

<template>
  <DialogRoot
    :open="props.open"
    @update:open="emit('update:open', $event)"
  >
    <DialogPortal>
      <DialogOverlay class="fixed inset-0 z-50 bg-surface-900/40 backdrop-blur-sm" />
      <DialogContent
        class="fixed left-1/2 top-1/2 z-50 w-[90vw] max-w-md -translate-x-1/2 -translate-y-1/2 rounded-3xl border border-surface-200 bg-white p-6 shadow-soft outline-none"
      >
        <div class="mb-5 flex items-start gap-4">
          <div class="flex h-12 w-12 shrink-0 items-center justify-center rounded-2xl bg-amber-100 text-amber-600">
            <FileWarning class="h-6 w-6" />
          </div>
          <div class="flex-1">
            <DialogTitle class="text-lg font-semibold text-surface-900">
              {{ title }}
            </DialogTitle>
            <DialogDescription class="mt-1 text-sm leading-relaxed text-surface-500">
              共 {{ fileConflicts.length }} 个文件冲突{{ folderMergeCount > 0 ? `，${folderMergeCount} 个文件夹将自动合并` : '' }}，请为每项选择处理方式。
            </DialogDescription>
          </div>
        </div>

        <div class="mb-4 flex gap-2">
          <button
            v-for="action in (['skip', 'overwrite', 'keep'] as ConflictStrategy[])"
            :key="action"
            class="flex-1 rounded-lg border border-surface-200 bg-surface-50 px-2 py-1.5 text-xs font-medium text-surface-600 transition-colors hover:border-primary-300 hover:bg-primary-50 hover:text-primary-700"
            @click="applyAll(action)"
          >
            全部{{ formatStrategyLabel(action) }}
          </button>
        </div>

        <div class="mb-5 max-h-64 space-y-3 overflow-y-auto">
          <div
            v-for="conflict in fileConflicts"
            :key="conflict.sourceId"
            class="rounded-2xl border border-surface-200 bg-surface-50/50 p-3"
          >
            <p class="mb-1 text-sm font-medium text-surface-800">
              {{ conflict.sourceName }}
            </p>
            <p
              v-if="conflict.sourcePath || conflict.targetPath"
              class="mb-2 text-xs text-surface-500"
            >
              {{ conflict.sourcePath ?? conflict.sourceName }} → {{ conflict.targetPath ?? conflict.existingName }}
            </p>
            <p
              v-else
              class="mb-2 text-xs text-surface-500"
            >
              目标位置已存在同名文件
              <span class="font-medium text-surface-700">"{{ conflict.existingName }}"</span>
            </p>
            <div class="grid grid-cols-3 gap-2">
              <button
                v-for="strategy in (['skip', 'overwrite', 'keep'] as ConflictStrategy[])"
                :key="strategy"
                :class="cn(
                  'rounded-xl border px-2 py-2 text-xs font-medium transition-colors',
                  strategies[conflict.sourceId] === strategy
                    ? 'border-primary-500 bg-primary-50 text-primary-700'
                    : 'border-surface-200 bg-white text-surface-600 hover:border-primary-300 hover:bg-primary-50/50'
                )"
                @click="setStrategy(conflict.sourceId, strategy)"
              >
                {{ formatStrategyLabel(strategy) }}
              </button>
            </div>
          </div>
        </div>

        <div class="flex justify-end gap-2">
          <button
            class="rounded-xl px-4 py-2 text-sm font-medium text-surface-600 transition-colors hover:bg-surface-100"
            @click="handleCancel"
          >
            取消
          </button>
          <button
            :disabled="!allResolved"
            :class="cn(
              'rounded-xl px-4 py-2 text-sm font-semibold shadow-soft transition-all active:scale-95',
              allResolved
                ? 'bg-primary-600 text-white hover:bg-primary-700 hover:shadow-card'
                : 'cursor-not-allowed bg-surface-200 text-surface-400'
            )"
            @click="handleConfirm"
          >
            {{ confirmText }}
          </button>
        </div>

        <button
          class="absolute right-4 top-4 rounded-lg p-1.5 text-surface-400 transition-colors hover:bg-surface-100 hover:text-surface-600"
          @click="handleCancel"
        >
          <X class="h-4 w-4" />
        </button>
      </DialogContent>
    </DialogPortal>
  </DialogRoot>
</template>
