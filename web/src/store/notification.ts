import { defineStore } from 'pinia'
import { ref } from 'vue'

export interface ToastMessage {
  id: string
  message: string
  type: 'error' | 'success' | 'info'
  duration: number
}

/**
 * 全局消息通知 Store。
 */
export const useNotificationStore = defineStore('notification', () => {
  const toasts = ref<ToastMessage[]>([])

  function show(message: string, type: ToastMessage['type'] = 'info', duration = 3000) {
    const id = `${Date.now()}-${Math.random().toString(36).slice(2, 9)}`
    const toast: ToastMessage = { id, message, type, duration }
    toasts.value.push(toast)

    if (duration > 0) {
      setTimeout(() => remove(id), duration)
    }
  }

  function error(message: string, duration = 3000) {
    show(message, 'error', duration)
  }

  function success(message: string, duration = 3000) {
    show(message, 'success', duration)
  }

  function remove(id: string) {
    const index = toasts.value.findIndex((t) => t.id === id)
    if (index >= 0) {
      toasts.value.splice(index, 1)
    }
  }

  return {
    toasts,
    show,
    error,
    success,
    remove,
  }
})
