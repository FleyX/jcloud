<script setup lang="ts">
/**
 * 新建文件夹弹窗
 */
import { ref, watch } from 'vue'
import { X, FolderPlus } from '@lucide/vue'
import { cn } from '@/utils/cn'

interface Props {
  open: boolean
  parentId?: string
}

const props = defineProps<Props>()
const emit = defineEmits<{
  close: []
  confirm: [name: string]
}>()

const name = ref('')
const error = ref('')

watch(
  () => props.open,
  (open) => {
    if (open) {
      name.value = ''
      error.value = ''
    }
  },
)

function handleConfirm() {
  const trimmed = name.value.trim()
  if (!trimmed) {
    error.value = '文件夹名称不能为空'
    return
  }
  if (trimmed.includes('/') || trimmed.includes('\\')) {
    error.value = '名称不能包含斜杠'
    return
  }
  emit('confirm', trimmed)
}

function handleClose() {
  emit('close')
}
</script>

<template>
  <div
    v-if="open"
    class="fixed inset-0 z-50 flex items-center justify-center bg-black/40 backdrop-blur-sm"
    @click.self="handleClose"
  >
    <div class="w-full max-w-sm rounded-3xl border border-surface-200 bg-white p-6 shadow-soft">
      <div class="mb-4 flex items-center gap-3">
        <div class="flex h-10 w-10 items-center justify-center rounded-xl bg-emerald-100 text-emerald-600">
          <FolderPlus class="h-5 w-5" />
        </div>
        <h3 class="text-lg font-semibold text-surface-900">
          新建文件夹
        </h3>
        <button
          class="ml-auto rounded-lg p-1 text-surface-400 hover:bg-surface-100"
          @click="handleClose"
        >
          <X class="h-4 w-4" />
        </button>
      </div>

      <input
        v-model="name"
        type="text"
        placeholder="请输入文件夹名称"
        class="mb-2 w-full rounded-xl border border-surface-200 bg-surface-50 px-4 py-2.5 text-sm outline-none transition-all focus:border-primary-300 focus:bg-white focus:ring-2 focus:ring-primary-100"
        @keyup.enter="handleConfirm"
      >
      <p
        v-if="error"
        class="mb-4 text-xs text-red-500"
      >
        {{ error }}
      </p>

      <div class="mt-4 flex justify-end gap-2">
        <button
          class="rounded-xl px-4 py-2 text-sm font-medium text-surface-600 transition-colors hover:bg-surface-100"
          @click="handleClose"
        >
          取消
        </button>
        <button
          :class="
            cn(
              'rounded-xl px-4 py-2 text-sm font-semibold text-white shadow-soft transition-all hover:shadow-card active:scale-95',
              name.trim() ? 'bg-primary-600 hover:bg-primary-700' : 'bg-surface-300 cursor-not-allowed'
            )
          "
          :disabled="!name.trim()"
          @click="handleConfirm"
        >
          创建
        </button>
      </div>
    </div>
  </div>
</template>
