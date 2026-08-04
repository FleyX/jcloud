<script setup lang="ts">
/**
 * 影视页内顶栏菜单（PC/移动端共用）
 * - 移动端：Tab 靠左、单行不换行、超出宽度横向滚动；操作插槽参与布局占位，不覆盖 Tab
 * - PC 端：操作插槽脱离 Tab 流固定右置，Tab 以整行宽度为基准居中
 * - 激活 Tab 高亮下划线，风格对齐页内顶栏图标按钮
 * - Tab 状态由父组件经路由 query 承载（?tab=favorites），本组件仅负责展示与回调
 */
import { cn } from '@/utils/cn'

interface MediaTopMenuTab {
  key: string
  label: string
}

interface Props {
  tabs: MediaTopMenuTab[]
  active: string
}

defineProps<Props>()
const emit = defineEmits<{
  select: [key: string]
}>()
</script>

<template>
  <div class="sticky top-0 z-20 border-b border-surface-100 bg-white/95 backdrop-blur">
    <div class="relative flex items-center px-2 md:px-4">
      <!-- Tab 区：移动端靠左、单行不换行、超出横向滚动；PC 端以整行宽度为基准居中 -->
      <div class="flex min-w-0 flex-1 justify-start overflow-x-auto whitespace-nowrap md:justify-center">
        <div class="flex w-max shrink-0 items-center gap-1">
          <button
            v-for="tab in tabs"
            :key="tab.key"
            class="relative px-4 py-3 text-sm transition-colors"
            :class="cn(
              tab.key === active
                ? 'font-semibold text-primary-600'
                : 'text-surface-500 hover:text-surface-800'
            )"
            @click="emit('select', tab.key)"
          >
            {{ tab.label }}
            <span
              v-if="tab.key === active"
              class="absolute inset-x-3 bottom-0 h-0.5 rounded-full bg-primary-500"
            />
          </button>
        </div>
      </div>
      <!-- 右侧操作插槽：移动端参与布局占位且不收缩，避免覆盖 Tab；PC 端脱离 Tab 流固定右置 -->
      <div class="flex shrink-0 items-center gap-1 md:absolute md:inset-y-0 md:right-4">
        <slot />
      </div>
    </div>
  </div>
</template>
