<script setup lang="ts">
/**
 * 通用批量冲突解决弹窗。
 * 用于恢复、移动、复制等场景下，为每个冲突项选择 skip / overwrite / auto_rename。
 */
import { ref, watch } from 'vue'
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
}

const props = withDefaults(defineProps<Props>(), {
  title: '文件冲突',
})

const emit = defineEmits<{
  'update:open': [value: boolean]
  confirm: [strategies: Record<string, ConflictStrategy>]
  cancel: []
}>()

const strategies = ref<Record<string, ConflictStrategy>>({})

function availableStrategies(conflict: ConflictItemVo): ConflictStrategy[] {
  return conflict.sourceType === 'folder'
    ? ['skip', 'overwrite']
    : ['skip', 'overwrite', 'auto_rename']
}

watch(
  () => props.conflicts,
  (conflicts) => {
    strategies.value = {}
    conflicts.forEach((conflict) => {
      strategies.value[conflict.sourceId] = conflict.sourceType === 'folder' ? 'skip' : 'auto_rename'
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
  close()
  emit('confirm', { ...strategies.value })
}

function setStrategy(sourceId: string, strategy: ConflictStrategy) {
  strategies.value[sourceId] = strategy
}

function applyAll(strategy: ConflictStrategy) {
  props.conflicts.forEach((conflict) => {
    if (availableStrategies(conflict).includes(strategy)) {
      strategies.value[conflict.sourceId] = strategy
    }
  })
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
              检测到 {{ conflicts.length }} 项同名冲突，请为每项选择处理方式。
            </DialogDescription>
          </div>
        </div>

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

        <div class="mb-5 max-h-64 space-y-3 overflow-y-auto">
          <div
            v-for="conflict in conflicts"
            :key="conflict.sourceId"
            class="rounded-2xl border border-surface-200 bg-surface-50/50 p-3"
          >
            <p class="mb-2 text-sm font-medium text-surface-800">
              {{ conflict.sourceName }}
            </p>
            <p class="mb-3 text-xs text-surface-500">
              目标位置已存在同名 {{ conflict.existingType === 'folder' ? '文件夹' : '文件' }}
              <span class="font-medium text-surface-700">"{{ conflict.existingName }}"</span>
            </p>
            <div :class="cn('grid gap-2', conflict.sourceType === 'folder' ? 'grid-cols-2' : 'grid-cols-3')">
              <button
                v-for="strategy in availableStrategies(conflict)"
                :key="strategy"
                :class="cn(
                  'rounded-xl border px-2 py-2 text-xs font-medium transition-colors',
                  strategies[conflict.sourceId] === strategy
                    ? 'border-primary-500 bg-primary-50 text-primary-700'
                    : 'border-surface-200 bg-white text-surface-600 hover:border-primary-300 hover:bg-primary-50/50'
                )"
                @click="setStrategy(conflict.sourceId, strategy)"
              >
                {{ strategy === 'skip' ? '跳过' : strategy === 'overwrite' ? '覆盖' : '自动重命名' }}
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
            class="rounded-xl bg-primary-600 px-4 py-2 text-sm font-semibold text-white shadow-soft transition-all hover:bg-primary-700 hover:shadow-card active:scale-95"
            @click="handleConfirm"
          >
            确认恢复
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
