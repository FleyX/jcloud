import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createWebHistory } from 'vue-router'
import MobileHeader from './MobileHeader.vue'
import MobileDrawer from './MobileDrawer.vue'
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
      { path: '/files/trash', component: { template: '<div>trash</div>' } },
      { path: '/files/share', component: { template: '<div>share</div>' } },
      { path: '/admin/users', component: { template: '<div>users</div>' } },
      { path: '/admin/roles', component: { template: '<div>roles</div>' } },
      { path: '/person', component: { template: '<div>person</div>' } },
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

  it('navigates to the person settings page when the avatar is clicked', async () => {
    const { wrapper, router } = await mountHeader('/files')
    const pushSpy = vi.spyOn(router, 'push')

    await wrapper.find('button.rounded-full').trigger('click')

    expect(pushSpy).toHaveBeenCalledWith('/person')
  })

  it('renders the person module title and person menus on /person', async () => {
    const { wrapper } = await mountHeader('/person')

    expect(wrapper.text()).toContain('个人设置')

    await wrapper.find('button.text-base').trigger('click')
    await wrapper.vm.$nextTick()

    expect(wrapper.text()).toContain('个人资料')
    expect(wrapper.text()).toContain('远程挂载')
    expect(wrapper.text()).toContain('WebDAV共享')
  })

  it('highlights the profile secondary menu key on /person', async () => {
    const { wrapper } = await mountHeader('/person')

    const drawer = wrapper.findComponent(MobileDrawer)
    expect(drawer.props('activeKey')).toBe('profile')
  })

  it('highlights the deepest matching secondary menu key', async () => {
    const { wrapper } = await mountHeader('/files/trash')

    const drawer = wrapper.findComponent(MobileDrawer)
    expect(drawer.props('activeKey')).toBe('trash')
  })

  it('highlights the root secondary menu key on the root route', async () => {
    const { wrapper } = await mountHeader('/files')

    const drawer = wrapper.findComponent(MobileDrawer)
    expect(drawer.props('activeKey')).toBe('all')
  })

  it('cycles the theme mode through system, light, and dark with matching labels', async () => {
    const { wrapper } = await mountHeader('/files')

    const button = wrapper.find('button[aria-label^="主题"]')
    expect(button.exists()).toBe(true)
    expect(button.attributes('aria-label')).toContain('跟随系统')

    await button.trigger('click')
    await wrapper.vm.$nextTick()
    expect(button.attributes('aria-label')).toContain('浅色')

    await button.trigger('click')
    await wrapper.vm.$nextTick()
    expect(button.attributes('aria-label')).toContain('深色')

    await button.trigger('click')
    await wrapper.vm.$nextTick()
    expect(button.attributes('aria-label')).toContain('跟随系统')
  })
})
