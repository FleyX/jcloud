import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { getCurrentUser, login, logout } from '@/api/auth'
import type { LoginVo, UserVo } from '@/types/auth'

const DEVICE_ID_KEY = 'jcloud_device_id'

/** 写操作后防抖刷新用户信息的定时器句柄 */
let refreshTimer: ReturnType<typeof setTimeout> | null = null

/**
 * 全局用户状态 Store
 * 维护登录态、用户信息、资源编码列表
 * 登录态经 cookie 承载（HttpOnly），前端不持有任何 token；登录态判定以 userInfo 为准。
 */
export const useUserStore = defineStore('user', () => {
  /** 设备标识：首次生成随机串后持久化复用，此后永不重生成。代表设备而非凭证 */
  const deviceId = ref<string>(localStorage.getItem(DEVICE_ID_KEY) ?? '')
  if (!deviceId.value) {
    deviceId.value = crypto.randomUUID()
    localStorage.setItem(DEVICE_ID_KEY, deviceId.value)
  }
  const userInfo = ref<UserVo | null>(null)
  const resources = ref<string[]>([])
  const dynamicRoutesAdded = ref(false)
  const initialized = ref<boolean>(true)

  const isLoggedIn = computed(() => !!userInfo.value)
  const isAdmin = computed(() => userInfo.value?.isAdmin === true)

  function setLoginData(data: LoginVo) {
    // 设备标识以后端回显为准更新（正常等于我们上报的值）
    deviceId.value = data.deviceId
    localStorage.setItem(DEVICE_ID_KEY, data.deviceId)
    userInfo.value = data.userInfo
    resources.value = data.resources ?? []
    initialized.value = data.initialized ?? true
    dynamicRoutesAdded.value = false
  }

  /**
   * 用户登录
   */
  async function loginAction(username: string, password: string): Promise<LoginVo> {
    const data = await login({ username, password, deviceId: deviceId.value })
    setLoginData(data)
    return data
  }

  function markDynamicRoutesAdded() {
    dynamicRoutesAdded.value = true
  }

  /**
   * 获取当前登录用户信息（cookie 自动携带鉴权）
   */
  async function fetchCurrentUser(): Promise<LoginVo> {
    const data = await getCurrentUser()
    userInfo.value = data.userInfo
    resources.value = data.resources ?? []
    initialized.value = data.initialized ?? true
    return data
  }

  /**
   * 写操作后防抖刷新用户信息（合并连续操作的多次触发，刷新容量等字段）。
   * 失败静默：请求异常已由全局拦截统一展示，不阻断业务操作。
   */
  function scheduleUserInfoRefresh(delayMs = 800) {
    if (refreshTimer !== null) clearTimeout(refreshTimer)
    refreshTimer = setTimeout(() => {
      refreshTimer = null
      fetchCurrentUser().catch(() => {})
    }, delayMs)
  }

  /**
   * 登出：先 best-effort 吊销当前设备会话（fire-and-forget，失败不影响本地清理），
   * 再清除登录态（cookie 由后端登出接口清除）。
   * 设备标识代表设备而非会话，登出后保留，后续登录复用同一标识。
   * 保持同步签名：调用方（Header.vue 等）无需感知异步吊销。
   */
  function logoutAction() {
    logout().catch(() => {})
    userInfo.value = null
    resources.value = []
    initialized.value = true
    dynamicRoutesAdded.value = false
  }

  /**
   * 判断当前用户是否拥有指定资源编码（如 VIEW:/admin/users）。
   * 超级管理员直接返回 true。
   */
  function hasResource(code: string): boolean {
    if (isAdmin.value) {
      return true
    }
    return resources.value.includes(code)
  }

  /**
   * 判断当前用户是否拥有任意一个资源编码。
   * 超级管理员直接返回 true。
   */
  function hasAnyResource(codes: string[]): boolean {
    if (isAdmin.value) {
      return true
    }
    return codes.some((code) => resources.value.includes(code))
  }

  return {
    deviceId,
    userInfo,
    resources,
    dynamicRoutesAdded,
    initialized,
    isLoggedIn,
    isAdmin,
    loginAction,
    fetchCurrentUser,
    scheduleUserInfoRefresh,
    logoutAction,
    hasResource,
    hasAnyResource,
    markDynamicRoutesAdded,
  }
})
