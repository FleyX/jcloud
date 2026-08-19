import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import ProfilePage from './profile.vue'
import { getCurrentUserProfile, changePassword } from '@/api/user'
import type { UserProfileVo } from '@/types/auth'

vi.mock('@/api/user', () => ({
  getCurrentUserProfile: vi.fn(),
  updateCurrentUserProfile: vi.fn(),
  changePassword: vi.fn(),
}))

// DeviceSessionPanel 挂载于 profile 页内，mock 掉其依赖的设备 API，避免测试触发真实网络请求
vi.mock('@/api/auth', () => ({
  listDevices: vi.fn().mockResolvedValue([]),
  revokeDevice: vi.fn(),
  logoutAll: vi.fn(),
  logout: vi.fn(),
}))

async function createTestRouter() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/', component: { template: '<div />' } }],
  })
  await router.push('/')
  await router.isReady()
  return router
}

function buildProfile(): UserProfileVo {
  return {
    id: '1',
    username: 'admin',
    nickname: '超级管理员',
    email: 'admin@example.com',
    webdavEnabled: false,
  } as UserProfileVo
}

async function mountPage() {
  const router = await createTestRouter()
  const wrapper = mount(ProfilePage, {
    global: { plugins: [createPinia(), router] },
  })
  await flushPromises()
  return wrapper
}

describe('person/profile', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    vi.mocked(getCurrentUserProfile).mockResolvedValue(buildProfile())
  })

  it('loads the current profile into the form on mount', async () => {
    const wrapper = await mountPage()

    const inputs = wrapper.findAll('input')
    const usernameInput = inputs[0]
    const nicknameInput = inputs[1]
    const emailInput = inputs[2]

    expect((usernameInput.element as HTMLInputElement).value).toBe('admin')
    expect((nicknameInput.element as HTMLInputElement).value).toBe('超级管理员')
    expect((emailInput.element as HTMLInputElement).value).toBe('admin@example.com')
  })

  it('shows a validation error when the new passwords do not match', async () => {
    const wrapper = await mountPage()

    const passwordInputs = wrapper.findAll('input[type="password"]')
    await passwordInputs[0].setValue('old-password')
    await passwordInputs[1].setValue('new-password')
    await passwordInputs[2].setValue('different-password')

    const submitButtons = wrapper.findAll('button').filter((b) => b.text() === '保存')
    await submitButtons[1].trigger('click')

    expect(wrapper.text()).toContain('两次输入的新密码不一致')
    expect(changePassword).not.toHaveBeenCalled()
  })

  it('submits the password change and shows a success message', async () => {
    vi.mocked(changePassword).mockResolvedValue(undefined)
    const wrapper = await mountPage()

    const passwordInputs = wrapper.findAll('input[type="password"]')
    await passwordInputs[0].setValue('old-password')
    await passwordInputs[1].setValue('new-password')
    await passwordInputs[2].setValue('new-password')

    const submitButtons = wrapper.findAll('button').filter((b) => b.text() === '保存')
    await submitButtons[1].trigger('click')
    await flushPromises()

    expect(changePassword).toHaveBeenCalledWith({
      currentPassword: 'old-password',
      newPassword: 'new-password',
      confirmPassword: 'new-password',
    })
    expect(wrapper.text()).toContain('密码修改成功')
  })
})
