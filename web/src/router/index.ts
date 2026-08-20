import { createRouter, createWebHistory } from 'vue-router'
import type { RouteRecordRaw } from 'vue-router'
import { useUserStore } from '@/store/user'
import { deviceView } from '@/utils/device'

type RouteName = 'Login' | 'Register' | 'NotFound' | 'Forbidden' | 'Init' | 'Files' | 'Trash' | 'Share' | 'RemoteMount' | 'UserManagement' | 'RoleManagement' | 'StorageSpaceManagement' | 'AdminMediaSettings' | 'PersonProfile' | 'PersonDevices' | 'PersonWebDav' | 'MediaHome' | 'MediaLibrary' | 'MediaDirectories' | 'MediaMovieDetail' | 'MediaSeriesDetail' | 'MediaPlay'

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
    path: '/403',
    name: 'Forbidden' as RouteName,
    component: () => import('@/views/Forbidden.vue'),
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
 * - /admin/users 需要 VIEW:/admin/users 资源
 */
const dynamicRoutes: RouteRecordRaw[] = [
  {
    path: '/files',
    name: 'Files' as RouteName,
    component: deviceView('files/index'),
    meta: { resource: 'VIEW:/files', title: '全部文件' },
  },
  {
    path: '/files/trash',
    name: 'Trash' as RouteName,
    component: deviceView('files/trash'),
    meta: { resource: 'VIEW:/files', title: '回收站' },
  },
  {
    path: '/files/share',
    name: 'Share' as RouteName,
    component: deviceView('files/share'),
    meta: { resource: 'VIEW:/files', title: '我的分享' },
  },
  {
    path: '/person',
    name: 'PersonProfile' as RouteName,
    component: deviceView('person/profile'),
    meta: { title: '个人资料' },
  },
  {
    path: '/person/devices',
    name: 'PersonDevices' as RouteName,
    component: deviceView('person/devices'),
    meta: { title: '登录设备' },
  },
  {
    path: '/person/remote-mounts',
    name: 'RemoteMount' as RouteName,
    component: deviceView('files/remote-mounts'),
    meta: { title: '远程挂载' },
  },
  {
    path: '/person/webdav',
    name: 'PersonWebDav' as RouteName,
    component: deviceView('person/webdav'),
    meta: { title: 'WebDAV共享' },
  },
  // 旧路径重定向，保持兼容
  {
    path: '/profile',
    redirect: '/person',
  },
  {
    path: '/files/remote-mounts',
    redirect: '/person/remote-mounts',
  },
  {
    path: '/media',
    name: 'MediaHome' as RouteName,
    component: deviceView('media/home'),
    meta: { resource: 'VIEW:/media', title: '影视' },
  },
  {
    path: '/media/libraries/:id',
    name: 'MediaLibrary' as RouteName,
    component: deviceView('media/library'),
    meta: { resource: 'VIEW:/media', title: '媒体库' },
  },
  {
    path: '/media/directories',
    name: 'MediaDirectories' as RouteName,
    component: deviceView('media/directories'),
    meta: { resource: 'VIEW:/media', title: '目录管理' },
  },
  {
    path: '/media/movies/:id',
    name: 'MediaMovieDetail' as RouteName,
    component: deviceView('media/movie-detail'),
    meta: { resource: 'VIEW:/media', title: '电影详情' },
  },
  {
    path: '/media/series/:id',
    name: 'MediaSeriesDetail' as RouteName,
    component: deviceView('media/series-detail'),
    meta: { resource: 'VIEW:/media', title: '电视剧详情' },
  },
  {
    path: '/media/play/:id',
    name: 'MediaPlay' as RouteName,
    component: deviceView('media/play'),
    meta: { resource: 'VIEW:/media', title: '播放', standalone: true },
  },
  // 旧路径重定向，保持兼容
  {
    path: '/media/movies',
    redirect: '/media',
  },
  {
    path: '/media/series',
    redirect: '/media',
  },
  {
    path: '/media/others',
    redirect: '/media',
  },
  {
    path: '/admin/users',
    name: 'UserManagement' as RouteName,
    component: deviceView('admin/Users'),
    meta: { resource: 'VIEW:/admin/users', title: '用户管理' },
  },
  {
    path: '/admin/roles',
    name: 'RoleManagement' as RouteName,
    component: deviceView('admin/Roles'),
    meta: { resource: 'VIEW:/admin/roles', title: '角色管理' },
  },
  {
    path: '/admin/storage-spaces',
    name: 'StorageSpaceManagement' as RouteName,
    component: deviceView('admin/StorageSpaces'),
    meta: { resource: 'VIEW:/admin/storage-spaces', title: '存储空间管理' },
  },
  {
    path: '/admin/media',
    name: 'AdminMediaSettings' as RouteName,
    component: deviceView('admin/MediaSettings'),
    meta: { resource: 'VIEW:/admin/media', title: '影视设置' },
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
        const required = route.meta?.resource as string | undefined
        return !required || userStore.hasResource(required)
      })

  routesToAdd.forEach((route) => {
    // 避免重复添加同名路由
    if (!router.hasRoute(route.name as RouteName)) {
      router.addRoute(route)
    }
  })
}

const publicPaths = publicRoutes.map((route) => route.path)

/**
 * 判断目标路径是否对应某个需要权限才能访问的受保护动态路由。
 * 用于在路由未匹配时区分“无权限”与“页面不存在”。
 */
function isProtectedRoutePath(path: string): boolean {
  return dynamicRoutes.some((route) => {
    const required = route.meta?.resource as string | undefined
    if (!required) {
      return false
    }
    const routePath = route.path
    return path === routePath || path.startsWith(`${routePath}/`)
  })
}

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
      // 后端启动中或网络暂时不可用时保留本地 token，避免重启竞态导致用户被迫重新登录。
      // 401 由 request 层确认 token 无效并清空后，再进入登录页。
      if (userStore.token) {
        return next(false)
      }
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

  // 动态路由注入后仍匹配不到：
  // - 若目标路径属于某个需要权限的受保护路由，说明当前用户无权限，跳转 403
  // - 否则为真正的 404
  if (to.matched.length === 0) {
    return next(isProtectedRoutePath(to.path) ? '/403' : '/404')
  }

  next()
})

export default router
