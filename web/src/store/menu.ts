import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { useUserStore } from '@/store/user'

export type PrimaryModule = 'files' | 'media' | 'notes' | 'todos' | 'system' | 'person'

// person 为虚拟一级模块：不在顶部一级导航中渲染，仅用于路由推导与二级菜单联动
export const primaryModuleList: PrimaryModule[] = ['files', 'media', 'notes', 'todos', 'system', 'person']

export interface SecondaryMenuItem {
  key: string
  label: string
  icon?: string
  route?: string
}

/**
 * 一级模块级布局配置
 * - routePrefix：模块路由前缀，二级菜单为空时用于路由反查与模块入口跳转
 * - hideSidebar：隐藏二级菜单（PC 端不渲染左侧边栏，移动端抽屉不展示菜单列表）
 * - resource：无二级菜单时判断模块可见性所需的权限资源
 */
export interface PrimaryModuleOptions {
  routePrefix?: string
  hideSidebar?: boolean
  resource?: string
}

/**
 * 一级模块布局配置表（按模块维度配置，不做模块硬编码特例）
 * 影视模块为 Jellyfin 风单页导航，启用 hideSidebar
 */
const primaryModuleOptions: Partial<Record<PrimaryModule, PrimaryModuleOptions>> = {
  media: { routePrefix: '/media', hideSidebar: true, resource: 'VIEW:/media' },
}

/**
 * 指定一级模块是否隐藏二级菜单
 */
export function isSidebarHidden(primary: PrimaryModule): boolean {
  return primaryModuleOptions[primary]?.hideSidebar === true
}

/**
 * 根据当前路由反查其所属的一级模块
 * - 优先精确匹配，其次匹配以该路由为前缀的子路径
 * - 无二级菜单匹配时按模块 routePrefix 兜底（如影视模块）
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
  for (const [primary, options] of Object.entries(primaryModuleOptions) as [PrimaryModule, PrimaryModuleOptions][]) {
    const prefix = options.routePrefix
    if (prefix && (routePath === prefix || routePath.startsWith(`${prefix}/`))) {
      return primary
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
        if (!userStore.isAdmin && !userStore.hasResource('VIEW:/files')) {
          return []
        }
        return [
          { key: 'all', label: '全部文件', route: '/files' },
          { key: 'share', label: '我的分享', route: '/files/share' },
          { key: 'trash', label: '回收站', route: '/files/trash' },
        ]
      case 'media':
        // 影视模块启用 hideSidebar，不再提供二级菜单；可见性由 getPrimaryHomeRoute 的 resource 校验
        return []
      case 'notes':
        if (!userStore.isAdmin && !userStore.hasResource('VIEW:/notes')) {
          return []
        }
        return [
          { key: 'recent', label: '最近笔记', route: '/notes' },
          { key: 'tags', label: '标签', route: '/notes/tags' },
        ]
      case 'todos':
        if (!userStore.isAdmin && !userStore.hasResource('VIEW:/todos')) {
          return []
        }
        return [
          { key: 'today', label: '今日待办', route: '/todos' },
          { key: 'archive', label: '归档', route: '/todos/archive' },
        ]
      case 'system':
        return buildSystemMenus()
      case 'person':
        return [
          { key: 'profile', label: '个人资料', route: '/person' },
          { key: 'remote-mounts', label: '远程挂载', route: '/person/remote-mounts' },
          { key: 'webdav', label: 'WebDAV共享', route: '/person/webdav' },
        ]
      default:
        return []
    }
  }

  function buildSystemMenus(): SecondaryMenuItem[] {
    const menus: SecondaryMenuItem[] = []
    if (userStore.isAdmin || userStore.hasResource('VIEW:/admin/users')) {
      menus.push({ key: 'users', label: '用户管理', route: '/admin/users' })
    }
    if (userStore.isAdmin || userStore.hasResource('VIEW:/admin/roles')) {
      menus.push({ key: 'roles', label: '角色管理', route: '/admin/roles' })
    }
    if (userStore.isAdmin || userStore.hasResource('VIEW:/admin/storage-spaces')) {
      menus.push({ key: 'storage-spaces', label: '存储空间管理', route: '/admin/storage-spaces' })
    }
    if (userStore.isAdmin || userStore.hasResource('VIEW:/admin/media')) {
      menus.push({ key: 'media', label: '影视', route: '/admin/media' })
    }
    return menus
  }

  /**
   * 获取一级模块的入口路由
   * - 优先取第一个二级菜单路由
   * - 无二级菜单（hideSidebar 模块）时取 routePrefix，并按 resource 校验可见性
   * - 无权限或未配置时返回 undefined
   */
  function getPrimaryHomeRoute(primary: PrimaryModule): string | undefined {
    const menuRoute = getSecondaryMenusByPrimary(primary)[0]?.route
    if (menuRoute) {
      return menuRoute
    }
    const options = primaryModuleOptions[primary]
    if (!options?.routePrefix) {
      return undefined
    }
    if (options.resource && !userStore.isAdmin && !userStore.hasResource(options.resource)) {
      return undefined
    }
    return options.routePrefix
  }

  function setPrimary(module: PrimaryModule) {
    activePrimary.value = module
    activeSecondary.value = secondaryMenus.value[0]?.key ?? ''
  }

  function setSecondary(key: string) {
    activeSecondary.value = key
  }

  function buildMenusMap(): Record<PrimaryModule, SecondaryMenuItem[]> {
    return primaryModuleList.reduce(
      (acc, primary) => {
        acc[primary] = getSecondaryMenusByPrimary(primary)
        return acc
      },
      {} as Record<PrimaryModule, SecondaryMenuItem[]>,
    )
  }

  /**
   * 根据当前 URL 反查并同步一、二级菜单高亮状态
   * - 公开路由或未匹配到任何模块时不修改当前状态
   */
  function syncWithRoute(routePath: string) {
    const menus = buildMenusMap()
    const primary = resolvePrimaryModuleByRoute(routePath, menus)
    if (!primary) {
      return
    }

    setPrimary(primary)

    const matched = secondaryMenus.value
      .filter((item) => {
        const route = item.route
        if (!route) return false
        return routePath === route || routePath.startsWith(`${route}/`)
      })
      .sort((a, b) => (b.route?.length ?? 0) - (a.route?.length ?? 0))[0]

    if (matched) {
      activeSecondary.value = matched.key
    }
  }

  return {
    activePrimary,
    activeSecondary,
    secondaryMenus,
    getSecondaryMenusByPrimary,
    getPrimaryHomeRoute,
    setPrimary,
    setSecondary,
    syncWithRoute,
  }
})
