<script setup lang="ts">
/**
 * 媒体搜索弹窗：输入防抖 300ms 实时触发搜索
 */
import { ref, watch } from 'vue'
import { X, Search } from '@lucide/vue'

interface Props {
  open: boolean
  initialKeyword?: string
  placeholder?: string
}

const props = withDefaults(defineProps<Props>(), {
  initialKeyword: '',
  placeholder: '搜索文件名、剧名（电影名）、简介',
})
const emit = defineEmits<{
  close: []
  search: [keyword: string]
}>()

const input = ref('')
let timer: ReturnType<typeof setTimeout> | null = null

watch(
  () => props.open,
  (open) => {
    if (open) input.value = props.initialKeyword
  },
)

watch(input, (value) => {
  if (!props.open) return
  if (timer) clearTimeout(timer)
  timer = setTimeout(() => emit('search', value), 300)
})

function clear() {
  input.value = ''
  emit('search', '')
}
</script>

<template>
  <div
    v-if="open"
    class="fixed inset-0 z-50 flex items-start justify-center bg-black/40 pt-32 backdrop-blur-sm"
    @click.self="emit('close')"
  >
    <div class="w-full max-w-md rounded-3xl border border-surface-200 bg-white p-5 shadow-soft">
      <div class="flex items-center gap-3">
        <Search class="h-4 w-4 shrink-0 text-surface-400" />
        <input
          v-model="input"
          type="text"
          :placeholder="placeholder"
          class="flex-1 bg-transparent text-sm text-surface-800 outline-none placeholder:text-surface-300"
          autofocus
        >
        <button
          v-if="input"
          class="rounded-lg p-1 text-surface-400 hover:bg-surface-100"
          title="清空"
          @click="clear"
        >
          <X class="h-4 w-4" />
        </button>
        <button
          class="rounded-lg p-1 text-surface-400 hover:bg-surface-100"
          title="关闭"
          @click="emit('close')"
        >
          <X class="h-4 w-4" />
        </button>
      </div>
    </div>
  </div>
</template>
