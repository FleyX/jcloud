import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { getCurrentUser, login } from '@/api/auth'
import type { LoginVo, UserVo } from '@/types/auth'

const TOKEN_KEY = 'jcloud_token'

/**
 * 全局用户状态 Store
 * 维护登录态、用户信息、权限编码列表
 */
export const useUserStore = defineStore('user', () => {
  const token = ref<string>(localStorage.getItem(TOKEN_KEY) ?? '')
  const userInfo = ref<UserVo | null>(null)
  const permissions = ref<string[]>([])
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
    permissions.value = data.permissions ?? []
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
    permissions.value = data.permissions ?? []
    initialized.value = data.initialized ?? true
    return data
  }

  /**
   * 登出
   */
  function logoutAction() {
    setToken('')
    userInfo.value = null
    permissions.value = []
    initialized.value = true
    dynamicRoutesAdded.value = false
  }

  /**
   * 判断当前用户是否拥有指定权限编码。
   * 超级管理员直接返回 true。
   */
  function hasPermission(code: string): boolean {
    if (isAdmin.value) {
      return true
    }
    return permissions.value.includes(code)
  }

  /**
   * 判断当前用户是否拥有任意一个权限编码。
   * 超级管理员直接返回 true。
   */
  function hasAnyPermission(codes: string[]): boolean {
    if (isAdmin.value) {
      return true
    }
    return codes.some((code) => permissions.value.includes(code))
  }

  return {
    token,
    userInfo,
    permissions,
    dynamicRoutesAdded,
    initialized,
    isLoggedIn,
    isAdmin,
    loginAction,
    fetchCurrentUser,
    logoutAction,
    hasPermission,
    hasAnyPermission,
    markDynamicRoutesAdded,
  }
})
