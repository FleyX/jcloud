import type { Directive, DirectiveBinding } from 'vue'
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
let ancestorMap: Map<string, Set<string>> | null = null

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
 * 获取并缓存权限树。首次调用时加载，后续复用。
 * 加载失败时重置缓存，允许后续指令重试。
 */
async function ensurePermissionTree(signal?: AbortSignal): Promise<PermissionTreeVo[]> {
  if (!permissionTreePromise) {
    permissionTreePromise = fetchPermissionTree().catch((err) => {
      permissionTreePromise = null
      throw err
    })
  }

  const tree = await permissionTreePromise

  if (signal?.aborted) {
    throw new Error('aborted')
  }

  if (!ancestorMap) {
    ancestorMap = buildAncestorMap(tree)
  }

  return tree
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

  if (!ancestorMap) {
    ancestorMap = buildAncestorMap(tree)
  }

  // 用户拥有的某个权限是 requiredCode 的后代（即 requiredCode 是该权限的祖先）
  return userCodes.some((code) => ancestorMap!.get(code)?.has(requiredCode))
}

type PermissionValue = string | string[]

const controllers = new WeakMap<Element, AbortController>()
const originalDisplays = new WeakMap<HTMLElement, string>()

function removeElement(el: HTMLElement) {
  if (el.parentNode) {
    el.parentNode.removeChild(el)
  }
}

async function checkPermission(el: HTMLElement, binding: DirectiveBinding<PermissionValue>) {
  const userStore = useUserStore()

  const requiredCodes = Array.isArray(binding.value) ? binding.value : [binding.value]
  if (requiredCodes.length === 0 || requiredCodes.some((code) => !code)) {
    removeElement(el)
    return
  }

  // 取消同一元素上一次的异步校验
  let controller = controllers.get(el)
  if (controller) {
    controller.abort()
  }
  controller = new AbortController()
  controllers.set(el, controller)
  const { signal } = controller

  // 保存原始 display 样式，校验期间隐藏元素避免无权限内容闪烁
  if (!originalDisplays.has(el)) {
    originalDisplays.set(el, el.style.display)
  }
  const originalDisplay = originalDisplays.get(el) ?? ''
  el.style.display = 'none'

  const userCodes = userStore.permissions

  // 超级管理员或直接拥有权限时立即放行
  if (userStore.isAdmin || requiredCodes.some((code) => userCodes.includes(code))) {
    if (!signal.aborted) {
      el.style.display = originalDisplay
    }
    return
  }

  // 没有任何权限且不是管理员，直接拒绝
  if (userCodes.length === 0) {
    if (!signal.aborted) {
      removeElement(el)
    }
    return
  }

  // 需要通过权限树判断后代继承关系
  try {
    const tree = await ensurePermissionTree(signal)

    const granted = requiredCodes.some((code) => hasPermission(code, userCodes, tree))

    if (!signal.aborted) {
      if (granted) {
        el.style.display = originalDisplay
      } else {
        removeElement(el)
      }
    }
  } catch {
    // 权限树加载失败或请求被取消时默认拒绝
    if (!signal.aborted) {
      removeElement(el)
    }
  }
}

export const vPermission: Directive<HTMLElement, PermissionValue> = {
  mounted: checkPermission,
  updated: checkPermission,
  unmounted(el) {
    const controller = controllers.get(el)
    if (controller) {
      controller.abort()
      controllers.delete(el)
    }
    originalDisplays.delete(el)
  },
}

export default vPermission
