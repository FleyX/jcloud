<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Cloud, ChevronLeft } from '@lucide/vue'
import { useMenuStore, resolvePrimaryModuleByRoute, primaryModuleList, type PrimaryModule } from '@/store/menu'
import { useUserStore } from '@/store/user'
import MobileDrawer from './MobileDrawer.vue'
import type { SecondaryMenuItem } from '@/store/menu'


const router = useRouter()
const route = useRoute()
const menuStore = useMenuStore()
const userStore = useUserStore()

const isProfilePage = computed(() => route.path === '/profile')

const primaryLabels: Record<PrimaryModule, string> = {
  files: '文件',
  notes: '笔记',
  todos: '待办',
  system: '系统',
}

const resolvedPrimary = computed<PrimaryModule | null>(() => {
  if (isProfilePage.value) return null
  const menus = primaryModuleList.reduce<Record<PrimaryModule, SecondaryMenuItem[]>>(
    (acc, primary) => {
      acc[primary] = menuStore.getSecondaryMenusByPrimary(primary)
      return acc
    },
    {} as Record<PrimaryModule, SecondaryMenuItem[]>,
  )
  return resolvePrimaryModuleByRoute(route.path, menus)
})

const headerTitle = computed(() => {
  if (isProfilePage.value) return '个人中心'
  return primaryLabels[resolvedPrimary.value ?? menuStore.activePrimary]
})

const drawerMenus = computed(() => {
  if (isProfilePage.value || !resolvedPrimary.value) return []
  return menuStore.getSecondaryMenusByPrimary(resolvedPrimary.value)
})

const activeSecondaryKey = computed(() => {
  const currentPath = route.path
  const match = drawerMenus.value.find((menu) => {
    const menuRoute = menu.route
    if (!menuRoute) return false
    return currentPath === menuRoute || currentPath.startsWith(`${menuRoute}/`)
  })
  return match?.key ?? ''
})

const drawerOpen = ref(false)

function handleLogoClick() {
  const target = menuStore.getSecondaryMenusByPrimary('files')[0]?.route ?? '/files'
  router.replace(target)
}

function handleBack() {
  router.back()
}

function handleAvatarClick() {
  router.push('/profile')
}

function handleDrawerSelect(item: SecondaryMenuItem) {
  if (item.route) {
    menuStore.setSecondary(item.key)
    router.replace(item.route)
  }
}
</script>

<template>
  <header
    class="flex h-14 shrink-0 items-center justify-between border-b border-surface-200 bg-white/80 px-4 backdrop-blur-md"
  >
    <!-- 左侧：返回 或 Logo -->
    <div class="flex w-14 items-center">
      <button
        v-if="isProfilePage"
        class="flex items-center gap-0.5 text-sm text-surface-600"
        @click="handleBack"
      >
        <ChevronLeft class="h-5 w-5" />
        返回
      </button>
      <button
        v-else
        class="flex h-9 w-9 items-center justify-center rounded-xl bg-primary-600 text-white shadow-soft"
        @click="handleLogoClick"
      >
        <Cloud class="h-5 w-5" />
      </button>
    </div>

    <!-- 中间：模块标题 或 页面标题 -->
    <button
      v-if="!isProfilePage"
      class="flex items-center gap-1 text-base font-bold text-surface-900"
      @click="drawerOpen = true"
    >
      {{ headerTitle }}
      <span class="text-xs text-surface-400">▼</span>
    </button>
    <span
      v-else
      class="text-base font-bold text-surface-900"
    >{{ headerTitle }}</span>

    <!-- 右侧：头像 或 占位 -->
    <button
      v-if="!isProfilePage"
      class="flex h-9 w-9 items-center justify-center rounded-full bg-primary-100 text-sm font-bold text-primary-700 ring-2 ring-white"
      @click="handleAvatarClick"
    >
      {{ userStore.userInfo?.username?.charAt(0).toUpperCase() || 'U' }}
    </button>
    <div
      v-else
      class="w-9"
    />
  </header>

  <MobileDrawer
    v-if="!isProfilePage"
    v-model:open="drawerOpen"
    :title="headerTitle"
    :menus="drawerMenus"
    :active-key="activeSecondaryKey"
    @select="handleDrawerSelect"
  />
</template>
