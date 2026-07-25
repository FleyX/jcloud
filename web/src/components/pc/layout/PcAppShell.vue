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
        <!-- 注意：不要在此包裹 <transition mode="out-in">，异步路由组件会导致旧视图永远不卸载 -->
        <router-view v-slot="{ Component, route }">
          <component
            :is="Component"
            :key="route.fullPath"
          />
        </router-view>
      </main>
    </div>

    <TransferPanel />
    <TransferTaskPanel />
  </div>
</template>
