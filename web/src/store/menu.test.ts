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
      { key: 'share', label: '我的分享', route: '/files/share' },
      { key: 'trash', label: '回收站', route: '/files/trash' },
    ],
    notes: [],
    todos: [],
    system: [
      { key: 'users', label: '用户管理', route: '/admin/users' },
      { key: 'roles', label: '角色管理', route: '/admin/roles' },
      { key: 'permissions', label: '权限管理', route: '/admin/permissions' },
      { key: 'storage-spaces', label: '存储空间管理', route: '/admin/storage-spaces' },
    ],
    person: [
      { key: 'profile', label: '个人资料', route: '/person' },
      { key: 'remote-mounts', label: '远程挂载', route: '/person/remote-mounts' },
      { key: 'webdav', label: 'WebDAV共享', route: '/person/webdav' },
    ],
  }
}

describe('resolvePrimaryModuleByRoute', () => {
  it('resolves /files to the files primary module', () => {
    expect(resolvePrimaryModuleByRoute('/files', buildMenus())).toBe('files')
  })

  it('resolves nested files routes to the files primary module', () => {
    expect(resolvePrimaryModuleByRoute('/files/share', buildMenus())).toBe('files')
    expect(resolvePrimaryModuleByRoute('/files/trash', buildMenus())).toBe('files')
  })

  it('resolves admin routes to the system primary module', () => {
    expect(resolvePrimaryModuleByRoute('/admin/users', buildMenus())).toBe('system')
    expect(resolvePrimaryModuleByRoute('/admin/roles', buildMenus())).toBe('system')
    expect(resolvePrimaryModuleByRoute('/admin/permissions', buildMenus())).toBe('system')
  })

  it('resolves person routes to the person primary module', () => {
    expect(resolvePrimaryModuleByRoute('/person', buildMenus())).toBe('person')
    expect(resolvePrimaryModuleByRoute('/person/remote-mounts', buildMenus())).toBe('person')
    expect(resolvePrimaryModuleByRoute('/person/webdav', buildMenus())).toBe('person')
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

    expect(menus.map((menu) => menu.key)).toEqual(['users', 'roles', 'permissions', 'storage-spaces'])
  })

  it('returns only user management for a user with only user:menu permission', () => {
    const userStore = useUserStore()
    userStore.userInfo = buildRegularUser()
    userStore.permissions = ['user:menu']

    const menuStore = useMenuStore()
    const menus = menuStore.getSecondaryMenusByPrimary('system')

    expect(menus.map((menu) => menu.key)).toEqual(['users'])
  })

  it('returns person menus with profile, remote-mounts and webdav', () => {
    const userStore = useUserStore()
    userStore.userInfo = buildRegularUser()

    const menuStore = useMenuStore()
    const menus = menuStore.getSecondaryMenusByPrimary('person')

    expect(menus.map((menu) => menu.key)).toEqual(['profile', 'remote-mounts', 'webdav'])
  })

  it('does not include remote-mounts in files menus', () => {
    const userStore = useUserStore()
    userStore.userInfo = buildAdminUser()

    const menuStore = useMenuStore()
    const menus = menuStore.getSecondaryMenusByPrimary('files')

    expect(menus.map((menu) => menu.key)).toEqual(['all', 'share', 'trash'])
  })
})

describe('menuStore syncWithRoute', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    localStorage.clear()
  })

  it('syncs /files/trash to files primary and trash secondary', () => {
    const userStore = useUserStore()
    userStore.permissions = ['file:menu']

    const menuStore = useMenuStore()
    menuStore.syncWithRoute('/files/trash')

    expect(menuStore.activePrimary).toBe('files')
    expect(menuStore.activeSecondary).toBe('trash')
  })

  it('syncs /admin/storage-spaces to system primary and storage-spaces secondary', () => {
    const userStore = useUserStore()
    userStore.userInfo = buildAdminUser()

    const menuStore = useMenuStore()
    menuStore.syncWithRoute('/admin/storage-spaces')

    expect(menuStore.activePrimary).toBe('system')
    expect(menuStore.activeSecondary).toBe('storage-spaces')
    expect(menuStore.secondaryMenus.map((menu) => menu.key)).toContain('storage-spaces')
  })

  it('syncs /admin/roles to system primary and roles secondary', () => {
    const userStore = useUserStore()
    userStore.userInfo = buildAdminUser()

    const menuStore = useMenuStore()
    menuStore.syncWithRoute('/admin/roles')

    expect(menuStore.activePrimary).toBe('system')
    expect(menuStore.activeSecondary).toBe('roles')
  })

  it('syncs /person/webdav to person primary and webdav secondary', () => {
    const userStore = useUserStore()
    userStore.userInfo = buildAdminUser()

    const menuStore = useMenuStore()
    menuStore.syncWithRoute('/person/webdav')

    expect(menuStore.activePrimary).toBe('person')
    expect(menuStore.activeSecondary).toBe('webdav')
  })

  it('syncs /person/remote-mounts to person primary and remote-mounts secondary', () => {
    const userStore = useUserStore()
    userStore.userInfo = buildAdminUser()

    const menuStore = useMenuStore()
    menuStore.syncWithRoute('/person/remote-mounts')

    expect(menuStore.activePrimary).toBe('person')
    expect(menuStore.activeSecondary).toBe('remote-mounts')
  })

  it('does not change state for routes that do not belong to any primary module', () => {
    const userStore = useUserStore()
    userStore.userInfo = buildAdminUser()

    const menuStore = useMenuStore()
    menuStore.syncWithRoute('/profile')

    expect(menuStore.activePrimary).toBe('files')
    expect(menuStore.activeSecondary).toBe('all')
  })
})
