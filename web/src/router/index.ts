import { createRouter, createWebHistory } from 'vue-router'
import type { RouteRecordRaw } from 'vue-router'
import { useUserStore } from '@/store/user'
import { deviceView } from '@/utils/device'

type RouteName = 'Login' | 'Register' | 'NotFound' | 'Init' | 'Files' | 'Trash' | 'Share' | 'UserManagement' | 'RoleManagement' | 'PermissionManagement' | 'StorageSpaceManagement' | 'Profile'

/**
 * 公开静态路由
 */
const publicRoutes: RouteRecordRaw[] = [
  {
    path: '/',
    redirect: '/files',
  },
  {
    path: '/login',
    name: 'Login' as RouteName,
    component: deviceView('auth/Login'),
    meta: { public: true },
  },
  {
    path: '/register',
    name: 'Register' as RouteName,
    component: deviceView('auth/Register'),
    meta: { public: true },
  },
  {
    path: '/init',
    name: 'Init' as RouteName,
    component: deviceView('init/index'),
    meta: { init: true },
  },
  {
    path: '/404',
    name: 'NotFound' as RouteName,
    component: () => import('@/views/NotFound.vue'),
    meta: { public: true },
  },
  {
    path: '/s/:code',
    name: 'PublicShare' as RouteName,
    component: deviceView('share/index'),
    meta: { public: true },
  },
]

/**
 * 动态路由：登录后根据权限注入
 * - /files 为基础模块，无权限要求
 * - /admin/users 需要 user:menu 权限
 */
const dynamicRoutes: RouteRecordRaw[] = [
  {
    path: '/files',
    name: 'Files' as RouteName,
    component: deviceView('files/index'),
    meta: { title: '全部文件' },
  },
  {
    path: '/files/trash',
    name: 'Trash' as RouteName,
    component: deviceView('files/trash'),
    meta: { title: '回收站' },
  },
  {
    path: '/files/share',
    name: 'Share' as RouteName,
    component: deviceView('files/share'),
    meta: { title: '我的分享' },
  },
  {
    path: '/admin/users',
    name: 'UserManagement' as RouteName,
    component: deviceView('admin/Users'),
    meta: { permission: 'user:menu', title: '用户管理' },
  },
  {
    path: '/admin/roles',
    name: 'RoleManagement' as RouteName,
    component: deviceView('admin/Roles'),
    meta: { permission: 'role:menu', title: '角色管理' },
  },
  {
    path: '/admin/permissions',
    name: 'PermissionManagement' as RouteName,
    component: deviceView('admin/Permissions'),
    meta: { permission: 'permission:menu', title: '权限管理' },
  },
  {
    path: '/admin/storage-spaces',
    name: 'StorageSpaceManagement' as RouteName,
    component: deviceView('admin/StorageSpaces'),
    meta: { permission: 'storage_space:menu', title: '存储空间管理' },
  },
  {
    path: '/profile',
    name: 'Profile' as RouteName,
    component: deviceView('user/Profile'),
    meta: { title: '个人中心', hideTabBar: true },
  },
]

const router = createRouter({
  history: createWebHistory(),
  routes: publicRoutes,
})

/**
 * 根据当前用户权限注入动态路由
 * admin 直接注入全部动态路由
 */
function addDynamicRoutes(userStore: ReturnType<typeof useUserStore>) {
  const routesToAdd = userStore.isAdmin
    ? dynamicRoutes
    : dynamicRoutes.filter((route) => {
        const required = route.meta?.permission as string | undefined
        return !required || userStore.hasPermission(required)
      })

  routesToAdd.forEach((route) => {
    // 避免重复添加同名路由
    if (!router.hasRoute(route.name as RouteName)) {
      router.addRoute(route)
    }
  })
}

const publicPaths = publicRoutes.map((route) => route.path)

function needsInitRedirect(userStore: ReturnType<typeof useUserStore>): boolean {
  return userStore.isAdmin && !userStore.initialized
}

router.beforeEach(async (to, _from, next) => {
  const userStore = useUserStore()

  // 公开路由直接放行
  if (to.meta.public || publicPaths.includes(to.path)) {
    return next()
  }

  // 未登录则跳转登录页
  if (!userStore.token) {
    return next('/login')
  }

  // 需要用户信息但尚未加载时，先拉取用户信息并注入动态路由
  if (!userStore.dynamicRoutesAdded) {
    try {
      if (!userStore.userInfo) {
        await userStore.fetchCurrentUser()
      }
      addDynamicRoutes(userStore)
      userStore.markDynamicRoutesAdded()
      // 初始化页面按初始化状态处理
      if (to.meta.init) {
        if (needsInitRedirect(userStore)) {
          return next()
        }
        return next('/files')
      }
      // 管理员未初始化时强制进入初始化页
      if (needsInitRedirect(userStore)) {
        return next('/init')
      }
      // 重新解析目标路由
      return next({ ...to, replace: true })
    } catch {
      userStore.logoutAction()
      return next('/login')
    }
  }

  // 初始化页面：已完成初始化则跳走
  if (to.meta.init) {
    if (needsInitRedirect(userStore)) {
      return next()
    }
    return next('/files')
  }

  // 管理员未初始化时强制进入初始化页
  if (needsInitRedirect(userStore)) {
    return next('/init')
  }

  // 动态路由注入后仍匹配不到，则视为 404
  if (to.matched.length === 0) {
    return next('/404')
  }

  next()
})

export default router
