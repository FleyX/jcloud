<script setup lang="ts">
/**
 * 影视页内顶栏菜单（PC/移动端共用）
 * - 居中 Tab（移动端可横向滚动）+ 右侧图标按钮插槽（搜索/配置等）
 * - 激活 Tab 高亮下划线，风格对齐 MediaWallToolbar 排序按钮组
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
      <!-- 居中 Tab：flex-1 撑满 + justify-center，overflow-x-auto 支持移动端横滑 -->
      <div class="flex min-w-0 flex-1 justify-center overflow-x-auto whitespace-nowrap">
        <div class="flex items-center gap-1">
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
      <!-- 右侧图标按钮：绝对定位避免挤压 Tab 居中 -->
      <div class="absolute right-2 flex items-center gap-1 md:right-4">
        <slot />
      </div>
    </div>
  </div>
</template>
