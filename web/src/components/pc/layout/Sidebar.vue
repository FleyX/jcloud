<script setup lang="ts">
/**
 * 左侧边栏（二级菜单）
 * - 根据 Pinia 中当前激活的一级模块联动渲染二级菜单
 * - 文件模块下展示：全部文件、正在传输、我的分享、回收站
 * - 底部展示个人空间容量进度条
 */
import { computed, watch, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useMenuStore } from '@/store/menu'
import { useUserStore } from '@/store/user'

import {
  FolderOpen,
  Share2,
  Trash2,
  HardDrive,
  Shield,
  FileText,
  CheckSquare,
  Globe,
} from '@lucide/vue'
import { cn } from '@/utils/cn'
import type { Component } from 'vue'

const router = useRouter()
const route = useRoute()
const menuStore = useMenuStore()
const userStore = useUserStore()

const iconMap: Record<string, Component> = {
  all: FolderOpen,
  share: Share2,
  trash: Trash2,
  'remote-mounts': Globe,
  recent: FileText,
  tags: FileText,
  today: CheckSquare,
  archive: CheckSquare,
  users: Shield,
}

function bytesToGB(bytes?: string | number): number {
  const num = Number(bytes)
  if (!num) return 0
  return num / 1024 / 1024 / 1024
}

const usedGB = computed(() => bytesToGB(userStore.userInfo?.usedSpace))
const totalGB = computed(() => bytesToGB(userStore.userInfo?.quota))
const isUnlimited = computed(() => userStore.userInfo?.quota === '0')
const usagePercent = computed(() => {
  if (totalGB.value <= 0) return 0
  return Math.min((usedGB.value / totalGB.value) * 100, 100)
})

function handleMenuClick(item: { key: string; route?: string }) {
  menuStore.setSecondary(item.key)
  if (item.route) {
    router.push(item.route)
  }
}

const showCapacityWidget = computed(() => menuStore.activePrimary === 'files')

function syncMenuWithRoute(path: string) {
  menuStore.syncWithRoute(path)
}

onMounted(() => syncMenuWithRoute(route.path))
watch(() => route.path, syncMenuWithRoute)
</script>

<template>
  <aside
    class="flex w-64 shrink-0 flex-col border-r border-surface-200 bg-white/60 backdrop-blur-sm"
  >
    <!-- 菜单区域 -->
    <nav class="flex-1 px-3 py-5">
      <ul class="space-y-1">
        <li
          v-for="item in menuStore.secondaryMenus"
          :key="item.key"
        >
          <button
            :class="
              cn(
                'group flex w-full items-center gap-3 rounded-xl px-4 py-2.5 text-sm font-medium transition-all duration-200 ease-out-expo',
                menuStore.activeSecondary === item.key
                  ? 'bg-primary-50 text-primary-700 shadow-sm'
                  : 'text-surface-600 hover:bg-surface-100 hover:text-surface-900'
              )
            "
            @click="handleMenuClick(item)"
          >
            <component
              :is="iconMap[item.key] || FolderOpen"
              :class="
                cn(
                  'h-[18px] w-[18px] transition-transform duration-200',
                  menuStore.activeSecondary === item.key
                    ? 'text-primary-600'
                    : 'text-surface-400 group-hover:text-surface-600'
                )
              "
            />
            <span class="flex-1 text-left">{{ item.label }}</span>
          </button>
        </li>
      </ul>
    </nav>

    <!-- 底部容量微件 -->
    <div
      v-if="showCapacityWidget"
      class="border-t border-surface-200 p-4"
    >
      <!-- 容量卡片 -->
      <div
        class="rounded-2xl border border-surface-200 bg-gradient-to-br from-white to-surface-50 p-4 shadow-card transition-shadow duration-300 hover:shadow-soft"
      >
        <div class="mb-3 flex items-center gap-2">
          <div class="flex h-8 w-8 items-center justify-center rounded-lg bg-primary-100">
            <HardDrive class="h-4 w-4 text-primary-600" />
          </div>
          <div>
            <p class="text-xs font-medium text-surface-500">
              个人空间
            </p>
            <p class="text-sm font-semibold text-surface-900">
              {{ usedGB.toFixed(2) }} GB / {{ isUnlimited ? '不限制' : `${totalGB.toFixed(2)} GB` }}
            </p>
          </div>
        </div>

        <!-- 进度条 -->
        <template v-if="!isUnlimited">
          <div class="h-2 w-full overflow-hidden rounded-full bg-surface-200">
            <div
              class="h-full rounded-full bg-gradient-to-r from-primary-500 to-primary-400 transition-all duration-700 ease-out-expo"
              :style="{ width: `${usagePercent}%` }"
            />
          </div>
          <p class="mt-2 text-right text-[10px] text-surface-400">
            已用 {{ usagePercent.toFixed(1) }}%
          </p>
        </template>
      </div>
    </div>
  </aside>
</template>
