<script setup lang="ts">
/**
 * 应用根组件
 * - 全局挂载 Toast、ConfirmDialog
 * - 公开路由使用全屏独立布局
 * - 其他路由根据设备类型分发到 PC 或移动端外壳
 */
import { computed, watch } from 'vue'
import { useRoute } from 'vue-router'
import { useDeviceStore } from '@/store/device'
import { useThemeStore } from '@/store/theme'
import { useRemoteMountHealthCheck } from '@/composables/useRemoteMountHealthCheck'
import PcAppShell from '@/components/pc/layout/PcAppShell.vue'
import MobileAppShell from '@/components/mobile/layout/MobileAppShell.vue'
import ConfirmDialog from '@/components/ui/ConfirmDialog.vue'
import Toast from '@/components/ui/Toast.vue'

const route = useRoute()
const deviceStore = useDeviceStore()
const themeStore = useThemeStore()

useRemoteMountHealthCheck()

const isStandaloneRoute = computed(() => !!route.meta.public || !!route.meta.init || !!route.meta.standalone)

watch(
  () => route.meta.standalone === true,
  (isPlayerRoute) => themeStore.setThemeEnabled(!isPlayerRoute),
  { immediate: true },
)
</script>

<template>
  <Toast />
  <ConfirmDialog />

  <template v-if="isStandaloneRoute">
    <router-view />
  </template>

  <PcAppShell v-else-if="!deviceStore.isMobile" />
  <MobileAppShell v-else />
</template>
