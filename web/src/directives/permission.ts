import type { Directive } from 'vue'
import { useUserStore } from '@/store/user'

/**
 * 权限指令 v-permission
 * 用法：v-permission="'user:delete'" 或 v-permission="['user:delete', 'user:save']"
 * 非超级管理员且无对应权限时，直接移除 DOM 元素。
 */
export const vPermission: Directive<HTMLElement, string | string[]> = {
  mounted(el, binding) {
    const userStore = useUserStore()

    // 超级管理员直接放行
    if (userStore.isAdmin) {
      return
    }

    const codes = Array.isArray(binding.value) ? binding.value : [binding.value]
    const hasAuth = codes.some((code) => userStore.hasPermission(code))

    if (!hasAuth && el.parentNode) {
      el.parentNode.removeChild(el)
    }
  },
}
