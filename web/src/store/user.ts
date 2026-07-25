import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { getCurrentUser, login } from '@/api/auth'
import type { LoginVo, UserVo } from '@/types/auth'

const TOKEN_KEY = 'jcloud_token'

/**
 * 全局用户状态 Store
 * 维护登录态、用户信息、资源编码列表
 */
export const useUserStore = defineStore('user', () => {
  const token = ref<string>(localStorage.getItem(TOKEN_KEY) ?? '')
  const userInfo = ref<UserVo | null>(null)
  const resources = ref<string[]>([])
  const dynamicRoutesAdded = ref(false)
  const initialized = ref<boolean>(true)

  const isLoggedIn = computed(() => !!token.value && !!userInfo.value)
  const isAdmin = computed(() => userInfo.value?.isAdmin === true)

  function setToken(value: string) {
    token.value = value
    if (value) {
      localStorage.setItem(TOKEN_KEY, value)
    } else {
      localStorage.removeItem(TOKEN_KEY)
    }
  }

  function setLoginData(data: LoginVo) {
    setToken(data.token)
    userInfo.value = data.userInfo
    resources.value = data.resources ?? []
    initialized.value = data.initialized ?? true
    dynamicRoutesAdded.value = false
  }

  /**
   * 用户登录
   */
  async function loginAction(username: string, password: string): Promise<LoginVo> {
    const data = await login({ username, password })
    setLoginData(data)
    return data
  }

  function markDynamicRoutesAdded() {
    dynamicRoutesAdded.value = true
  }

  /**
   * 获取当前登录用户信息
   */
  async function fetchCurrentUser(): Promise<LoginVo> {
    const data = await getCurrentUser()
    userInfo.value = data.userInfo
    resources.value = data.resources ?? []
    initialized.value = data.initialized ?? true
    return data
  }

  /**
   * 登出
   */
  function logoutAction() {
    setToken('')
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
    token,
    userInfo,
    resources,
    dynamicRoutesAdded,
    initialized,
    isLoggedIn,
    isAdmin,
    loginAction,
    fetchCurrentUser,
    logoutAction,
    hasResource,
    hasAnyResource,
    markDynamicRoutesAdded,
  }
})
