import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import MobileDrawer from './MobileDrawer.vue'
import type { SecondaryMenuItem } from '@/store/menu'

function buildMenus(): SecondaryMenuItem[] {
  return [
    { key: 'users', label: '用户管理', route: '/admin/users' },
    { key: 'roles', label: '角色管理', route: '/admin/roles' },
  ]
}

describe('MobileDrawer', () => {
  it('renders secondary menu items when open', () => {
    const wrapper = mount(MobileDrawer, {
      props: { open: true, title: '系统', menus: buildMenus() },
      global: {
        stubs: {
          DialogPortal: { template: '<div><slot /></div>' },
        },
      },
    })

    expect(wrapper.text()).toContain('用户管理')
    expect(wrapper.text()).toContain('角色管理')
  })

  it('highlights the active secondary menu', () => {
    const wrapper = mount(MobileDrawer, {
      props: { open: true, title: '系统', menus: buildMenus(), activeKey: 'roles' },
      global: {
        stubs: {
          DialogPortal: { template: '<div><slot /></div>' },
        },
      },
    })

    const activeButton = wrapper.find('button.bg-primary-50')
    expect(activeButton.exists()).toBe(true)
    expect(activeButton.text()).toBe('角色管理')
  })

  it('emits select and closes when a menu item is clicked', async () => {
    const wrapper = mount(MobileDrawer, {
      props: { open: true, title: '系统', menus: buildMenus() },
      global: {
        stubs: {
          DialogPortal: { template: '<div><slot /></div>' },
        },
      },
    })

    const buttons = wrapper.findAll('nav button')
    expect(buttons.length).toBe(2)

    await buttons[1].trigger('click')

    expect(wrapper.emitted('select')).toHaveLength(1)
    expect(wrapper.emitted('update:open')).toEqual([[false]])
  })
})
