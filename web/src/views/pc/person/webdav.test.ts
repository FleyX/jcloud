import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import WebDavPage from './webdav.vue'
import { getCurrentUserProfile, toggleWebDav } from '@/api/user'
import type { UserProfileVo } from '@/types/auth'

vi.mock('@/api/user', () => ({
  getCurrentUserProfile: vi.fn(),
  toggleWebDav: vi.fn(),
}))

function buildProfile(webdavEnabled: boolean): UserProfileVo {
  return {
    id: '1',
    username: 'admin',
    webdavEnabled,
  } as UserProfileVo
}

async function mountPage() {
  const wrapper = mount(WebDavPage, {
    global: { plugins: [createPinia()] },
  })
  await flushPromises()
  return wrapper
}

describe('person/webdav', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  it('shows the WebDAV url when enabled', async () => {
    vi.mocked(getCurrentUserProfile).mockResolvedValue(buildProfile(true))
    const wrapper = await mountPage()

    const urlInput = wrapper.find('input[readonly]')
    expect(urlInput.exists()).toBe(true)
    expect((urlInput.element as HTMLInputElement).value).toBe(
      `${window.location.origin}/dav/admin`,
    )
  })

  it('hides the WebDAV url when disabled', async () => {
    vi.mocked(getCurrentUserProfile).mockResolvedValue(buildProfile(false))
    const wrapper = await mountPage()

    expect(wrapper.find('input[readonly]').exists()).toBe(false)
  })

  it('toggles WebDAV on when the switch is clicked', async () => {
    vi.mocked(getCurrentUserProfile).mockResolvedValue(buildProfile(false))
    vi.mocked(toggleWebDav).mockResolvedValue(buildProfile(true))
    const wrapper = await mountPage()

    await wrapper.find('button').trigger('click')
    await flushPromises()

    expect(toggleWebDav).toHaveBeenCalledWith({ enabled: true })
    expect(wrapper.find('input[readonly]').exists()).toBe(true)
  })
})
