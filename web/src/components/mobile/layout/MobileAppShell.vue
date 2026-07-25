<script setup lang="ts">
/**
 * 移动端布局外壳
 * - 主内容区占满剩余空间并独立滚动
 * - 底部 TabBar 提供一级模块切换
 */
import { watch } from 'vue'
import { useRoute } from 'vue-router'
import MobileHeader from '@/components/mobile/layout/MobileHeader.vue'
import MobileBottomNav from '@/components/mobile/layout/MobileBottomNav.vue'
import TransferPanel from '@/components/transfer/TransferPanel.vue'
import TransferTaskPanel from '@/components/transfer/TransferTaskPanel.vue'
import { useMenuStore } from '@/store/menu'

const route = useRoute()
const menuStore = useMenuStore()

/**
 * 同步菜单高亮与当前路由
 * - 保证底部 TabBar 与标题栏在直接访问/回退时显示正确的一级模块
 */
watch(() => route.path, (path) => menuStore.syncWithRoute(path), { immediate: true })
</script>

<template>
  <div class="flex h-screen w-screen flex-col overflow-hidden bg-surface-50">
    <MobileHeader />
    <main class="flex-1 overflow-y-auto">
      <router-view />
    </main>

    <MobileBottomNav v-if="!route.meta.hideTabBar" />

    <TransferPanel />
    <TransferTaskPanel />
  </div>
</template>
