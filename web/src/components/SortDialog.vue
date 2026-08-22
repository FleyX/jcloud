<script setup lang="ts" generic="T extends string">
/**
 * 通用排序弹窗：排序字段单选 + 排序方向单选，确认后由父组件应用排序
 * - 内部暂存选择，打开时同步 props 当前值
 * - 选中项用 Check 图标标记（仿 PlayerOptionMenu），样式对齐 MediaSearchModal
 */
import { shallowRef, watch, type ShallowRef } from 'vue'
import { ArrowUpDown, X, Check } from '@lucide/vue'
import { cn } from '@/utils/cn'

interface Props {
  open: boolean
  fields: Array<{ value: T; label: string }>
  sortField: T
  sortOrder: 'asc' | 'desc'
}

const props = defineProps<Props>()
const emit = defineEmits<{
  close: []
  confirm: [field: T, order: 'asc' | 'desc']
}>()

const orderOptions = [
  { value: 'asc' as const, label: '升序' },
  { value: 'desc' as const, label: '降序' },
]

const selectedField: ShallowRef<T> = shallowRef<T>('' as T)
const selectedOrder: ShallowRef<'asc' | 'desc'> = shallowRef<'asc' | 'desc'>('desc')

watch(
  () => props.open,
  (open) => {
    if (open) {
      selectedField.value = props.sortField
      selectedOrder.value = props.sortOrder
    }
  },
)

function confirm() {
  emit('confirm', selectedField.value, selectedOrder.value)
  emit('close')
}
</script>

<template>
  <div
    v-if="open"
    class="fixed inset-0 z-50 flex items-start justify-center bg-black/40 pt-32 backdrop-blur-sm"
    @click.self="emit('close')"
  >
    <div class="flex w-full max-w-sm flex-col rounded-3xl border border-surface-200 bg-white p-5 shadow-soft">
      <div class="flex items-center gap-3">
        <ArrowUpDown class="h-4 w-4 shrink-0 text-surface-400" />
        <p class="flex-1 text-sm font-semibold text-surface-800">
          排序
        </p>
        <button
          class="rounded-lg p-1 text-surface-400 hover:bg-surface-100"
          title="关闭"
          @click="emit('close')"
        >
          <X class="h-4 w-4" />
        </button>
      </div>

      <p class="mt-4 text-xs font-semibold text-surface-500">
        排序字段
      </p>
      <div class="mt-1.5 grid grid-cols-2 gap-1.5">
        <button
          v-for="field in fields"
          :key="field.value"
          class="flex items-center gap-2 rounded-xl border px-3 py-2 text-sm transition-colors"
          :class="
            cn(
              'text-left',
              selectedField === field.value
                ? 'border-primary-200 bg-primary-50 font-medium text-primary-600'
                : 'border-surface-200 text-surface-600 hover:bg-surface-50'
            )
          "
          @click="selectedField = field.value"
        >
          <Check
            :class="cn('h-4 w-4 shrink-0', selectedField === field.value ? 'opacity-100' : 'opacity-0')"
          />
          {{ field.label }}
        </button>
      </div>

      <p class="mt-4 text-xs font-semibold text-surface-500">
        排序方向
      </p>
      <div class="mt-1.5 grid grid-cols-2 gap-1.5">
        <button
          v-for="order in orderOptions"
          :key="order.value"
          class="flex items-center gap-2 rounded-xl border px-3 py-2 text-sm transition-colors"
          :class="
            cn(
              'text-left',
              selectedOrder === order.value
                ? 'border-primary-200 bg-primary-50 font-medium text-primary-600'
                : 'border-surface-200 text-surface-600 hover:bg-surface-50'
            )
          "
          @click="selectedOrder = order.value"
        >
          <Check
            :class="cn('h-4 w-4 shrink-0', selectedOrder === order.value ? 'opacity-100' : 'opacity-0')"
          />
          {{ order.label }}
        </button>
      </div>

      <button
        class="mt-5 w-full rounded-xl bg-primary-600 py-2 text-sm font-semibold text-white transition-colors hover:bg-primary-700"
        @click="confirm"
      >
        确认
      </button>
    </div>
  </div>
</template>
