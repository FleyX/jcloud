import { defineStore } from 'pinia'
import { ref } from 'vue'

export interface ConfirmOptions {
  title?: string
  message: string
  confirmText?: string
  cancelText?: string
  type?: 'danger' | 'primary'
}

/**
 * 全局确认弹窗 Store。
 */
export const useConfirmStore = defineStore('confirm', () => {
  const visible = ref(false)
  const title = ref('确认')
  const message = ref('')
  const confirmText = ref('确定')
  const cancelText = ref('取消')
  const type = ref<ConfirmOptions['type']>('primary')

  let resolvePromise: ((value: boolean) => void) | null = null

  function open(options: ConfirmOptions): Promise<boolean> {
    title.value = options.title ?? '确认'
    message.value = options.message
    confirmText.value = options.confirmText ?? '确定'
    cancelText.value = options.cancelText ?? '取消'
    type.value = options.type ?? 'primary'
    visible.value = true

    return new Promise<boolean>((resolve) => {
      resolvePromise = resolve
    })
  }

  function confirm() {
    visible.value = false
    resolvePromise?.(true)
    resolvePromise = null
  }

  function cancel() {
    visible.value = false
    resolvePromise?.(false)
    resolvePromise = null
  }

  return {
    visible,
    title,
    message,
    confirmText,
    cancelText,
    type,
    open,
    confirm,
    cancel,
  }
})
