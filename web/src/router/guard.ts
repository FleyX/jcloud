import type { RouteLocationRaw, Router } from 'vue-router'
import { UnauthorizedError } from '@/api/errors'
import { useUserStore } from '@/store/user'
import { dynamicRoutes, publicRoutes, type RouteName } from './routes'

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

/**
 * 是否有正在进行的导航守卫。
 * request 层 401 处理据此判断：守卫进行中不主动 push 登录页（避免与当前导航竞态），
 * 由守卫捕获 UnauthorizedError 后通过重定向完成跳转。
 */
let guardInFlight = false

export function isGuardInFlight(): boolean {
  return guardInFlight
}

/**
 * 注册全局前置守卫。
 * 公开路由放行 → 登录态恢复/动态路由注入 → 初始化重定向 → 403/404 错误分支，主流程只做编排。
 */
export function setupGuard(router: Router): void {
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

  router.beforeEach(async (to, _from, next) => {
    guardInFlight = true
    // 所有出口统一经 done 收尾，复位守卫进行标记；next 为重载签名，联合类型需分支调用
    const done = (param?: boolean | RouteLocationRaw) => {
      guardInFlight = false
      if (param === undefined) {
        next()
      } else if (typeof param === 'boolean') {
        next(param)
      } else {
        next(param)
      }
    }
    const userStore = useUserStore()

    // 公开路由直接放行
    if (to.meta.public || publicPaths.includes(to.path)) {
      return done()
    }

    // 需要用户信息但尚未加载时，先拉取用户信息（cookie 自动携带鉴权）并注入动态路由
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
            return done()
          }
          return done('/files')
        }
        // 管理员未初始化时强制进入初始化页
        if (needsInitRedirect(userStore)) {
          return done('/init')
        }
        // 重新解析目标路由
        return done({ ...to, replace: true })
      } catch (error) {
        // 登录态失效（401）：request 层已清登录态，由守卫重定向登录页；
        // 重定向属于正常导航流程，不会使首跳失败（next(false) 会拒绝 router.isReady() 导致应用无法挂载白屏）
        if (error instanceof UnauthorizedError) {
          return done('/login')
        }
        // 后端未启动/网络暂时不可用时保留现状（停滞在守卫）
        return done(false)
      }
    }

    // 初始化页面：已完成初始化则跳走
    if (to.meta.init) {
      if (needsInitRedirect(userStore)) {
        return done()
      }
      return done('/files')
    }

    // 管理员未初始化时强制进入初始化页
    if (needsInitRedirect(userStore)) {
      return done('/init')
    }

    // 动态路由注入后仍匹配不到：
    // - 若目标路径属于某个需要权限的受保护路由，说明当前用户无权限，跳转 403
    // - 否则为真正的 404
    if (to.matched.length === 0) {
      return done(isProtectedRoutePath(to.path) ? '/403' : '/404')
    }

    done()
  })
}