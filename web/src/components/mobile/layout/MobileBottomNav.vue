<script setup lang="ts">
/**
 * 移动端底部 TabBar
 * - 按当前已实现的一级模块渲染入口（文件、系统）
 * - 后续 notes / todos 模块实现后可从 menuStore 自动扩展
 */
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  useMenuStore,
  resolvePrimaryModuleByRoute,
  primaryModuleList,
  type PrimaryModule,
  type SecondaryMenuItem,
} from '@/store/menu'
import { Cloud, Film, Settings } from '@lucide/vue'
import { cn } from '@/utils/cn'
import type { Component } from 'vue'

const router = useRouter()
const route = useRoute()
const menuStore = useMenuStore()

interface TabItem {
  key: PrimaryModule
  label: string
  icon: Component
  route: string
}

const primaryModules: Array<{ key: PrimaryModule; label: string; icon: Component }> = [
  { key: 'files', label: '文件', icon: Cloud },
  { key: 'media', label: '影视', icon: Film },
  { key: 'system', label: '系统', icon: Settings },
]

const tabs = computed<TabItem[]>(() =>
  primaryModules
    .map((module) => {
      const menus = menuStore.getSecondaryMenusByPrimary(module.key)
      const targetRoute = menus[0]?.route
      return targetRoute ? { ...module, route: targetRoute } : null
    })
    .filter((item): item is TabItem => item !== null),
)

const activeKey = computed(() => {
  const menus = primaryModuleList.reduce<Record<PrimaryModule, SecondaryMenuItem[]>>(
    (acc, primary) => {
      acc[primary] = menuStore.getSecondaryMenusByPrimary(primary)
      return acc
    },
    {} as Record<PrimaryModule, SecondaryMenuItem[]>,
  )
  return resolvePrimaryModuleByRoute(route.path, menus) ?? menuStore.activePrimary
})

function handleTabClick(tab: TabItem) {
  menuStore.setPrimary(tab.key)
  router.replace(tab.route)
}
</script>

<template>
  <nav
    class="flex h-16 shrink-0 items-center justify-around border-t border-surface-200 bg-white/90 backdrop-blur-md"
  >
    <button
      v-for="tab in tabs"
      :key="tab.key"
      :class="
        cn(
          'flex flex-1 flex-col items-center justify-center gap-1 text-xs font-medium transition-colors duration-200',
          activeKey === tab.key ? 'text-primary-600' : 'text-surface-500'
        )
      "
      @click="handleTabClick(tab)"
    >
      <component
        :is="tab.icon"
        class="h-5 w-5"
      />
      <span>{{ tab.label }}</span>
    </button>
  </nav>
</template>
