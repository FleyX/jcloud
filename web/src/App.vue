<script setup lang="ts">
/**
 * 应用根组件
 * 采用平台级多功能分层布局：顶部 Header + 左侧 Sidebar + 右侧主内容区
 * 登录/注册等公开页面使用全屏独立布局
 */
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import Header from '@/components/layout/Header.vue'
import Sidebar from '@/components/layout/Sidebar.vue'
import ConfirmDialog from '@/components/ui/ConfirmDialog.vue'
import Toast from '@/components/ui/Toast.vue'

const route = useRoute()
const isPublicRoute = computed(() => !!route.meta.public)
</script>

<template>
  <Toast />
  <ConfirmDialog />

  <template v-if="isPublicRoute">
    <router-view />
  </template>

  <div v-else class="flex h-screen w-screen flex-col overflow-hidden bg-surface-50">
    <!-- 顶部一级导航 -->
    <Header />

    <!-- 下方内容区 -->
    <div class="flex flex-1 overflow-hidden">
      <!-- 左侧二级菜单 -->
      <Sidebar />

      <!-- 主内容区：各个子模块在此处独立滚动 -->
      <main class="flex-1 overflow-y-auto p-6">
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
    </div>
  </div>
</template>
