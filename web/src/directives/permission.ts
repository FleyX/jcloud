import type { Directive, DirectiveBinding } from 'vue'
import { useUserStore } from '@/store/user'

/**
 * 资源指令 v-permission
 * 用法：v-permission="'VIEW:/admin/users'" 或 v-permission="['VIEW:/admin/users', 'GET:/jcloud/api/users']"
 * 非超级管理员且不拥有任一资源编码时，直接移除 DOM 元素。
 * 资源编码由后端登录接口下发（含祖先权限展开），前端同步判断，无需再请求权限树。
 */

type PermissionValue = string | string[]

function removeElement(el: HTMLElement) {
  if (el.parentNode) {
    el.parentNode.removeChild(el)
  }
}

function checkPermission(el: HTMLElement, binding: DirectiveBinding<PermissionValue>) {
  const userStore = useUserStore()

  const requiredCodes = Array.isArray(binding.value) ? binding.value : [binding.value]
  if (requiredCodes.length === 0 || requiredCodes.some((code) => !code)) {
    removeElement(el)
    return
  }

  if (!userStore.hasAnyResource(requiredCodes)) {
    removeElement(el)
  }
}

export const vPermission: Directive<HTMLElement, PermissionValue> = {
  mounted: checkPermission,
  updated: checkPermission,
}

export default vPermission
