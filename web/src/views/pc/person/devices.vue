<script setup lang="ts">
/**
 * 登录设备页
 * - 展示并管理当前账号的全部设备会话（踢出单个、退出所有设备）
 */
import DeviceSessionPanel from '@/components/DeviceSessionPanel.vue'
</script>

<template>
  <div class="mx-auto max-w-2xl p-6">
    <DeviceSessionPanel />
  </div>
</template>
