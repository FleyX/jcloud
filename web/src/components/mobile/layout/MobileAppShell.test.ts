import { describe, it, expect, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createWebHistory } from 'vue-router'
import { h } from 'vue'
import MobileAppShell from './MobileAppShell.vue'
import { useUserStore } from '@/store/user'
import { useMenuStore } from '@/store/menu'
import type { UserVo } from '@/types/auth'

function filesPage() {
  return Promise.resolve({
    default: { render: () => h('div', { class: 'files-page' }, 'files content') },
  })
}

function profilePage() {
  return Promise.resolve({
    default: { render: () => h('div', { class: 'profile-page' }, 'profile content') },
  })
}

function usersPage() {
  return Promise.resolve({
    default: { render: () => h('div', { class: 'users-page' }, 'users content') },
  })
}

function buildAdminUser(): UserVo {
  return {
    id: '1',
    username: 'admin',
    status: 1,
    isAdmin: true,
    roles: [],
  }
}

async function mountShell(initialPath: string) {
  localStorage.clear()
  const pinia = createPinia()
  setActivePinia(pinia)

  const userStore = useUserStore()
  userStore.userInfo = buildAdminUser()

  const router = createRouter({
    history: createWebHistory(),
    routes: [
      { path: '/files', component: filesPage, meta: { title: '全部文件' } },
      { path: '/person', component: profilePage, meta: { title: '个人资料' } },
      { path: '/admin/users', component: usersPage, meta: { title: '用户管理' } },
    ],
  })

  const wrapper = mount(MobileAppShell, {
    global: {
      plugins: [pinia, router],
      stubs: {
        TransferPanel: { template: '<div />' },
        TransferTaskPanel: { template: '<div />' },
      },
    },
  })

  await router.push(initialPath)
  await router.isReady()
  await flushPromises()

  return { wrapper, router }
}

describe('MobileAppShell', () => {
  beforeEach(() => {
    localStorage.clear()
  })

  it('renders the files page content on direct access', async () => {
    const { wrapper } = await mountShell('/files')

    expect(wrapper.find('.files-page').exists()).toBe(true)
    expect(wrapper.text()).toContain('files content')
  })

  it('renders the files page content after navigating back from person settings', async () => {
    const { wrapper, router } = await mountShell('/person')

    expect(wrapper.find('.profile-page').exists()).toBe(true)

    await router.replace('/files')
    await flushPromises()

    expect(wrapper.find('.files-page').exists()).toBe(true)
    expect(wrapper.text()).toContain('files content')
  })

  it('syncs menu primary module with the current route', async () => {
    const { router } = await mountShell('/files')
    const menuStore = useMenuStore()

    expect(menuStore.activePrimary).toBe('files')

    await router.replace('/admin/users')
    await flushPromises()

    expect(menuStore.activePrimary).toBe('system')
  })
})
