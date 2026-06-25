<script setup lang="ts">
/**
 * 移动端布局外壳
 * - 主内容区占满剩余空间并独立滚动
 * - 底部 TabBar 提供一级模块切换
 */
import { useRoute } from 'vue-router'
import MobileHeader from '@/components/mobile/layout/MobileHeader.vue'
import MobileBottomNav from '@/components/mobile/layout/MobileBottomNav.vue'
import TransferPanel from '@/components/transfer/TransferPanel.vue'

const route = useRoute()
</script>

<template>
  <div class="flex h-screen w-screen flex-col overflow-hidden bg-surface-50">
    <MobileHeader />
    <main class="flex-1 overflow-y-auto">
      <router-view v-slot="{ Component }">
        <transition
          enter-active-class="transition-all duration-300 ease-out-expo"
          enter-from-class="opacity-0 translate-y-2"
          enter-to-class="opacity-100 translate-y-0"
          leave-active-class="transition-all duration-200 ease-in"
          leave-from-class="opacity-100 translate-y-0"
          leave-to-class="opacity-0 -translate-y-2"
          mode="out-in"
        >
          <component :is="Component" />
        </transition>
      </router-view>
    </main>

    <MobileBottomNav v-if="!route.meta.hideTabBar" />

    <TransferPanel />
  </div>
</template>
