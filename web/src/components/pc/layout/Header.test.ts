import { describe, it, expect, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createWebHistory } from 'vue-router'
import Header from './Header.vue'
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
    ],
  })

  const wrapper = mount(Header, {
    global: {
      plugins: [pinia, router],
    },
  })

  await router.push(routePath)
  await router.isReady()
  await wrapper.vm.$nextTick()

  return { wrapper }
}

describe('PC Header theme toggle', () => {
  beforeEach(() => {
    localStorage.clear()
    document.documentElement.classList.remove('dark')
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
