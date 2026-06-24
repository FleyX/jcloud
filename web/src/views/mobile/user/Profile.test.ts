import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createWebHistory } from 'vue-router'
import Profile from './Profile.vue'
import { useUserStore } from '@/store/user'
import type { UserVo } from '@/types/auth'

vi.mock('@/api/user', () => ({
  getCurrentUserProfile: vi.fn(),
  updateCurrentUserProfile: vi.fn(),
  changePassword: vi.fn(),
}))

function buildAdminUser(): UserVo {
  return {
    id: '1',
    username: 'admin',
    nickname: '管理员',
    email: 'admin@example.com',
    status: 1,
    isAdmin: true,
    roles: [],
  }
}

async function mountProfile() {
  localStorage.clear()
  const pinia = createPinia()
  setActivePinia(pinia)

  const userStore = useUserStore()
  userStore.userInfo = buildAdminUser()

  const router = createRouter({
    history: createWebHistory(),
    routes: [{ path: '/login', component: { template: '<div>login</div>' } }],
  })

  const wrapper = mount(Profile, {
    global: {
      plugins: [pinia, router],
      stubs: {
        ProfileDialog: { template: '<div data-testid="profile-dialog" />' },
        ChangePasswordDialog: { template: '<div data-testid="password-dialog" />' },
      },
    },
  })

  await router.isReady()
  await wrapper.vm.$nextTick()

  return { wrapper, router, userStore }
}

describe('Profile', () => {
  beforeEach(() => {
    localStorage.clear()
  })

  it('renders current user information', async () => {
    const { wrapper } = await mountProfile()

    expect(wrapper.text()).toContain('admin')
    expect(wrapper.text()).toContain('超级管理员')
    expect(wrapper.text()).toContain('管理员')
    expect(wrapper.text()).toContain('admin@example.com')
  })

  it('renders profile actions', async () => {
    const { wrapper } = await mountProfile()

    expect(wrapper.text()).toContain('个人信息')
    expect(wrapper.text()).toContain('修改密码')
    expect(wrapper.text()).toContain('退出登录')
  })

  it('logs out and redirects to login when logout is clicked', async () => {
    const { wrapper, router, userStore } = await mountProfile()
    const logoutSpy = vi.spyOn(userStore, 'logoutAction')
    const pushSpy = vi.spyOn(router, 'push')

    const logoutButton = wrapper.findAll('button').find((btn) => btn.text().includes('退出登录'))
    expect(logoutButton).toBeDefined()

    await logoutButton!.trigger('click')

    expect(logoutSpy).toHaveBeenCalled()
    expect(pushSpy).toHaveBeenCalledWith('/login')
  })
})
