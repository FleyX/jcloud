import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { detectDeviceChannel, type DeviceChannel } from '@/utils/device'

/**
 * 设备类型 Store
 *
 * 在应用启动时完成一次 UA 判定，后续由该 store 提供响应式的设备状态。
 */
export const useDeviceStore = defineStore('device', () => {
  const channel = ref<DeviceChannel>(detectDeviceChannel())
  const isMobile = computed(() => channel.value === 'mobile')

  return {
    channel,
    isMobile,
  }
})
