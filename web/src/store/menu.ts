import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { useUserStore } from '@/store/user'

export type PrimaryModule = 'files' | 'notes' | 'todos' | 'system'

export const primaryModuleList: PrimaryModule[] = ['files', 'notes', 'todos', 'system']

export interface SecondaryMenuItem {
  key: string
  label: string
  icon?: string
  route?: string
}

/**
 * 根据当前路由反查其所属的一级模块
 * - 优先精确匹配，其次匹配以该路由为前缀的子路径
 * - 不匹配不属于任何已知路由的情况
 */
export function resolvePrimaryModuleByRoute(
  routePath: string,
  menus: Record<PrimaryModule, SecondaryMenuItem[]>,
): PrimaryModule | null {
  for (const [primary, items] of Object.entries(menus) as [PrimaryModule, SecondaryMenuItem[]][]) {
    for (const item of items) {
      const route = item.route
      if (!route) continue
      if (routePath === route || routePath.startsWith(`${route}/`)) {
        return primary
      }
    }
  }
  return null
}

/**
 * 全局菜单状态 Store
 * 负责一级模块切换与二级菜单联动
 */
export const useMenuStore = defineStore('menu', () => {
  const userStore = useUserStore()

  // 当前激活的一级模块
  const activePrimary = ref<PrimaryModule>('files')

  // 当前激活的二级菜单 key
  const activeSecondary = ref<string>('all')

  // 根据一级模块动态渲染二级菜单
  const secondaryMenus = computed<SecondaryMenuItem[]>(() => getSecondaryMenusByPrimary(activePrimary.value))

  function getSecondaryMenusByPrimary(primary: PrimaryModule): SecondaryMenuItem[] {
    switch (primary) {
      case 'files':
        return [
          { key: 'all', label: '全部文件', route: '/files' },
          { key: 'transfer', label: '正在传输', route: '/files' },
          { key: 'share', label: '我的分享', route: '/files/share' },
          { key: 'trash', label: '回收站', route: '/files/trash' },
        ]
      case 'notes':
        return [
          { key: 'recent', label: '最近笔记', route: '/notes' },
          { key: 'tags', label: '标签', route: '/notes/tags' },
        ]
      case 'todos':
        return [
          { key: 'today', label: '今日待办', route: '/todos' },
          { key: 'archive', label: '归档', route: '/todos/archive' },
        ]
      case 'system':
        return buildSystemMenus()
      default:
        return []
    }
  }

  function buildSystemMenus(): SecondaryMenuItem[] {
    const menus: SecondaryMenuItem[] = []
    if (userStore.isAdmin || userStore.hasPermission('user:menu')) {
      menus.push({ key: 'users', label: '用户管理', route: '/admin/users' })
    }
    if (userStore.isAdmin || userStore.hasPermission('role:menu')) {
      menus.push({ key: 'roles', label: '角色管理', route: '/admin/roles' })
    }
    if (userStore.isAdmin || userStore.hasPermission('permission:menu')) {
      menus.push({ key: 'permissions', label: '权限管理', route: '/admin/permissions' })
    }
    if (userStore.isAdmin || userStore.hasPermission('storage_space:menu')) {
      menus.push({ key: 'storage-spaces', label: '存储空间管理', route: '/admin/storage-spaces' })
    }
    return menus
  }

  function setPrimary(module: PrimaryModule) {
    activePrimary.value = module
    activeSecondary.value = secondaryMenus.value[0]?.key ?? ''
  }

  function setSecondary(key: string) {
    activeSecondary.value = key
  }

  return {
    activePrimary,
    activeSecondary,
    secondaryMenus,
    getSecondaryMenusByPrimary,
    setPrimary,
    setSecondary,
  }
})
