import type { Directive } from 'vue'
import { useUserStore } from '@/store/user'
import { fetchPermissionTree } from '@/api/permission'
import type { PermissionTreeVo } from '@/types/auth'

/**
 * 权限指令 v-permission
 * 用法：v-permission="'user:delete'" 或 v-permission="['user:delete', 'user:save']"
 * 非超级管理员且无对应权限时，直接移除 DOM 元素。
 *
 * 权限校验规则（与后端保持一致）：
 * 1. 用户直接拥有某个权限编码，则通过。
 * 2. 用户拥有的某个权限编码是指定权限编码的后代，则通过（后代自动继承所有祖先权限）。
 * 3. 否则不通过。
 */

let permissionTreePromise: Promise<PermissionTreeVo[]> | null = null

/**
 * 获取并缓存权限树。首次调用时加载，后续复用。
 * 加载失败时返回空数组，由调用方按“默认拒绝”处理。
 */
function ensurePermissionTree(): Promise<PermissionTreeVo[]> {
  if (!permissionTreePromise) {
    permissionTreePromise = fetchPermissionTree().catch(() => [])
  }
  return permissionTreePromise
}

/**
 * 根据权限树构建“权限编码 -> 所有祖先编码集合”的映射。
 */
function buildAncestorMap(tree: PermissionTreeVo[]): Map<string, Set<string>> {
  const map = new Map<string, Set<string>>()

  function walk(node: PermissionTreeVo, ancestors: string[]) {
    map.set(node.code, new Set(ancestors))
    const nextAncestors = [...ancestors, node.code]
    node.children?.forEach((child) => walk(child, nextAncestors))
  }

  tree.forEach((root) => walk(root, []))
  return map
}

/**
 * 判断用户是否拥有指定权限编码。
 * @param requiredCode 需要校验的权限编码
 * @param userCodes 用户拥有的权限编码列表
 * @param tree 完整的权限树
 */
export function hasPermission(requiredCode: string, userCodes: string[], tree: PermissionTreeVo[]): boolean {
  // 直接拥有
  if (userCodes.includes(requiredCode)) {
    return true
  }

  const ancestorMap = buildAncestorMap(tree)

  // 用户拥有的某个权限是 requiredCode 的后代（即 requiredCode 是该权限的祖先）
  return userCodes.some((code) => ancestorMap.get(code)?.has(requiredCode))
}

export const vPermission: Directive<HTMLElement, string | string[]> = {
  async mounted(el, binding) {
    const userStore = useUserStore()

    // 超级管理员直接放行
    if (userStore.isAdmin) {
      return
    }

    const requiredCodes = Array.isArray(binding.value) ? binding.value : [binding.value]
    const userCodes = userStore.permissions

    // 异步加载权限树期间先隐藏元素，避免无权限内容闪烁
    el.style.display = 'none'

    try {
      const tree = await ensurePermissionTree()

      const granted = requiredCodes.some((code) => hasPermission(code, userCodes, tree))

      if (granted) {
        el.style.display = ''
      } else if (el.parentNode) {
        el.parentNode.removeChild(el)
      }
    } catch {
      // 权限树加载失败时默认拒绝
      if (el.parentNode) {
        el.parentNode.removeChild(el)
      }
    }
  },
}
