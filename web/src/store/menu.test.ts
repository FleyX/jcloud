import { describe, it, expect, beforeEach } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { resolvePrimaryModuleByRoute } from './menu'
import { useUserStore } from './user'
import { useMenuStore } from './menu'
import type { PrimaryModule, SecondaryMenuItem } from './menu'
import type { UserVo } from '@/types/auth'

function buildMenus(): Record<PrimaryModule, SecondaryMenuItem[]> {
  return {
    files: [
      { key: 'all', label: '全部文件', route: '/files' },
      { key: 'transfer', label: '正在传输', route: '/files/transfer' },
      { key: 'share', label: '我的分享', route: '/files/share' },
      { key: 'trash', label: '回收站', route: '/files/trash' },
    ],
    notes: [],
    todos: [],
    system: [
      { key: 'users', label: '用户管理', route: '/admin/users' },
      { key: 'roles', label: '角色管理', route: '/admin/roles' },
      { key: 'permissions', label: '权限管理', route: '/admin/permissions' },
    ],
  }
}

describe('resolvePrimaryModuleByRoute', () => {
  it('resolves /files to the files primary module', () => {
    expect(resolvePrimaryModuleByRoute('/files', buildMenus())).toBe('files')
  })

  it('resolves nested files routes to the files primary module', () => {
    expect(resolvePrimaryModuleByRoute('/files/transfer', buildMenus())).toBe('files')
    expect(resolvePrimaryModuleByRoute('/files/share', buildMenus())).toBe('files')
    expect(resolvePrimaryModuleByRoute('/files/trash', buildMenus())).toBe('files')
  })

  it('resolves admin routes to the system primary module', () => {
    expect(resolvePrimaryModuleByRoute('/admin/users', buildMenus())).toBe('system')
    expect(resolvePrimaryModuleByRoute('/admin/roles', buildMenus())).toBe('system')
    expect(resolvePrimaryModuleByRoute('/admin/permissions', buildMenus())).toBe('system')
  })

  it('returns null for routes that do not belong to any primary module', () => {
    expect(resolvePrimaryModuleByRoute('/profile', buildMenus())).toBeNull()
    expect(resolvePrimaryModuleByRoute('/unknown', buildMenus())).toBeNull()
  })
})

function buildAdminUser(): UserVo {
  return {
    id: '1',
    username: 'admin',
    status: 1,
    isAdmin: true,
    roles: [],
  }
}

function buildRegularUser(): UserVo {
  return {
    id: '2',
    username: 'user',
    status: 1,
    isAdmin: false,
    roles: [],
  }
}

describe('menuStore secondary menus', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    localStorage.clear()
  })

  it('returns all system menus for an admin', () => {
    const userStore = useUserStore()
    userStore.userInfo = buildAdminUser()

    const menuStore = useMenuStore()
    const menus = menuStore.getSecondaryMenusByPrimary('system')

    expect(menus.map((menu) => menu.key)).toEqual(['users', 'roles', 'permissions'])
  })

  it('returns only user management for a user with only user:menu permission', () => {
    const userStore = useUserStore()
    userStore.userInfo = buildRegularUser()
    userStore.permissions = ['user:menu']

    const menuStore = useMenuStore()
    const menus = menuStore.getSecondaryMenusByPrimary('system')

    expect(menus.map((menu) => menu.key)).toEqual(['users'])
  })
})
