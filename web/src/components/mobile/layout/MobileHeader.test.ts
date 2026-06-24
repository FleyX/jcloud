import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createWebHistory } from 'vue-router'
import MobileHeader from './MobileHeader.vue'
import { useUserStore } from '@/store/user'
import type { UserVo } from '@/types/auth'

function buildAdminUser(): UserVo {
  return {
    id: '1',
    username: 'admin',
    status: 1,
    isAdmin: true,
    roles: [],
  }
}

async function mountHeader(routePath: string) {
  localStorage.clear()
  const pinia = createPinia()
  setActivePinia(pinia)

  const userStore = useUserStore()
  userStore.userInfo = buildAdminUser()

  const router = createRouter({
    history: createWebHistory(),
    routes: [
      { path: '/files', component: { template: '<div>files</div>' } },
      { path: '/admin/users', component: { template: '<div>users</div>' } },
      { path: '/admin/roles', component: { template: '<div>roles</div>' } },
      { path: '/profile', component: { template: '<div>profile</div>' } },
    ],
  })

  const wrapper = mount(MobileHeader, {
    global: {
      plugins: [pinia, router],
      stubs: {
        DialogPortal: { template: '<div><slot /></div>' },
      },
    },
  })

  await router.push(routePath)
  await router.isReady()
  await wrapper.vm.$nextTick()

  return { wrapper, router }
}

describe('MobileHeader', () => {
  beforeEach(() => {
    localStorage.clear()
  })

  it('renders the primary module title and user avatar initial', async () => {
    const { wrapper } = await mountHeader('/files')

    expect(wrapper.text()).toContain('文件')
    expect(wrapper.text()).toContain('A')
  })

  it('opens the secondary menu drawer when the title is clicked', async () => {
    const { wrapper } = await mountHeader('/admin/users')

    expect(wrapper.find('nav').exists()).toBe(false)

    await wrapper.find('button.text-base').trigger('click')
    await wrapper.vm.$nextTick()

    expect(wrapper.find('nav').exists()).toBe(true)
    expect(wrapper.text()).toContain('用户管理')
    expect(wrapper.text()).toContain('角色管理')
  })

  it('navigates to the files home when the logo is clicked', async () => {
    const { wrapper, router } = await mountHeader('/admin/users')
    const pushSpy = vi.spyOn(router, 'replace')

    await wrapper.find('button.rounded-xl').trigger('click')

    expect(pushSpy).toHaveBeenCalledWith('/files')
  })

  it('navigates to the profile page when the avatar is clicked', async () => {
    const { wrapper, router } = await mountHeader('/files')
    const pushSpy = vi.spyOn(router, 'push')

    await wrapper.find('button.rounded-full').trigger('click')

    expect(pushSpy).toHaveBeenCalledWith('/profile')
  })

  it('renders a back button on the profile page', async () => {
    const { wrapper } = await mountHeader('/profile')

    expect(wrapper.text()).toContain('个人中心')
    expect(wrapper.text()).toContain('返回')
    expect(wrapper.find('button.rounded-full').exists()).toBe(false)
  })
})
