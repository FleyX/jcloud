<script setup lang="ts">
/**
 * PC 端布局外壳
 * - 顶部一级导航 Header
 * - 左侧二级菜单 Sidebar
 * - 右侧主内容区独立滚动
 */
import Header from '@/components/pc/layout/Header.vue'
import Sidebar from '@/components/pc/layout/Sidebar.vue'
import TransferPanel from '@/components/transfer/TransferPanel.vue'
import TransferTaskPanel from '@/components/transfer/TransferTaskPanel.vue'
</script>

<template>
  <div class="flex h-screen w-screen flex-col overflow-hidden bg-surface-50">
    <Header />

    <div class="flex flex-1 overflow-hidden">
      <Sidebar />

      <main class="flex-1 overflow-y-auto p-6">
        <router-view v-slot="{ Component, route }">
          <transition
            enter-active-class="transition-all duration-300 ease-out-expo"
            enter-from-class="opacity-0 translate-y-2"
            enter-to-class="opacity-100 translate-y-0"
            leave-active-class="transition-all duration-200 ease-in"
            leave-from-class="opacity-100 translate-y-0"
            leave-to-class="opacity-0 -translate-y-2"
            mode="out-in"
          >
            <component
              :is="Component"
              :key="route.fullPath"
            />
          </transition>
        </router-view>
      </main>
    </div>

    <TransferPanel />
    <TransferTaskPanel />
  </div>
</template>
