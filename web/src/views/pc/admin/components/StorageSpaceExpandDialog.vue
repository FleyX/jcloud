<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { StorageSpaceExpandDto, StorageSpaceVo } from '@/types/storage-space'
import { Database, X } from '@lucide/vue'
import {
  DialogClose,
  DialogContent,
  DialogDescription,
  DialogOverlay,
  DialogPortal,
  DialogRoot,
  DialogTitle,
} from 'radix-vue'

interface Props {
  open: boolean
  space: StorageSpaceVo | null
  submitting: boolean
}

const props = defineProps<Props>()
const emit = defineEmits<{
  'update:open': [value: boolean]
  submit: [dto: StorageSpaceExpandDto]
}>()

const capacity = ref('')

watch(() => props.space, (space) => {
  capacity.value = space?.capacity || ''
}, { immediate: true })

const localOpen = computed({
  get: () => props.open,
  set: (value) => emit('update:open', value),
})

function handleSubmit() {
  if (!props.space) return
  const dto: StorageSpaceExpandDto = {
    id: props.space.id,
    capacity: capacity.value,
  }
  emit('submit', dto)
}
</script>

<template>
  <DialogRoot v-model:open="localOpen">
    <DialogPortal>
      <DialogOverlay class="fixed inset-0 z-40 bg-surface-900/40 backdrop-blur-sm" />
      <DialogContent
        class="fixed left-1/2 top-1/2 z-50 w-full max-w-md -translate-x-1/2 -translate-y-1/2 rounded-2xl bg-white p-6 shadow-soft"
      >
        <div class="mb-4 flex items-center justify-between">
          <div class="flex items-center gap-2">
            <Database class="h-5 w-5 text-primary-600" />
            <DialogTitle class="text-lg font-bold text-surface-900">
              扩容存储空间
            </DialogTitle>
          </div>
          <DialogClose
            class="rounded-lg p-1 text-surface-400 outline-none hover:bg-surface-100 hover:text-surface-600"
          >
            <X class="h-5 w-5" />
          </DialogClose>
        </div>
        <DialogDescription class="mb-4 text-sm text-surface-500">
          修改 {{ space?.name }} 的总容量（字节）
        </DialogDescription>

        <div class="mb-6 space-y-4">
          <div>
            <label class="mb-1 block text-xs font-medium text-surface-700">新容量（字节）</label>
            <input
              v-model="capacity"
              type="text"
              placeholder="请输入容量字节数"
              class="w-full rounded-xl border border-surface-200 bg-surface-50 px-3 py-2 text-sm outline-none focus:border-primary-500 focus:ring-2 focus:ring-primary-100"
            >
          </div>
          <div class="text-xs text-surface-500">
            当前已用：{{ space?.usedSpace }} 字节
          </div>
        </div>

        <div class="flex justify-end gap-3">
          <DialogClose as-child>
            <button
              :disabled="submitting"
              class="rounded-xl border border-surface-200 bg-white px-4 py-2 text-sm font-medium text-surface-700 transition-colors hover:bg-surface-50 disabled:cursor-not-allowed disabled:opacity-60"
            >
              取消
            </button>
          </DialogClose>
          <button
            :disabled="submitting"
            class="rounded-xl bg-primary-600 px-4 py-2 text-sm font-medium text-white shadow-soft transition-colors hover:bg-primary-700 disabled:cursor-not-allowed disabled:opacity-70"
            @click="handleSubmit"
          >
            {{ submitting ? '扩容中...' : '确认扩容' }}
          </button>
        </div>
      </DialogContent>
    </DialogPortal>
  </DialogRoot>
</template>
