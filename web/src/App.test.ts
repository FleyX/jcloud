import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import App from './App.vue'

type MediaQueryListener = (event: MediaQueryListEvent) => void

function createMediaQuery(initialMatches: boolean) {
  const matches = initialMatches
  const listeners = new Set<MediaQueryListener>()
  const query = {
    media: '(prefers-color-scheme: dark)',
    get matches() {
      return matches
    },
    addEventListener: (_type: string, listener: MediaQueryListener) => listeners.add(listener),
    removeEventListener: (_type: string, listener: MediaQueryListener) => listeners.delete(listener),
    addListener: (listener: MediaQueryListener) => listeners.add(listener),
    removeListener: (listener: MediaQueryListener) => listeners.delete(listener),
  } as unknown as MediaQueryList

  return { query }
}

const originalMatchMedia = window.matchMedia

async function mountApp(initialPath: string) {
  const pinia = createPinia()
  setActivePinia(pinia)

  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', redirect: '/files' },
      { path: '/files', component: { template: '<div>files</div>' } },
      { path: '/media/play/:id', component: { template: '<div>player</div>' }, meta: { standalone: true } },
    ],
  })

  await router.push(initialPath)
  await router.isReady()

  const wrapper = mount(App, {
    global: {
      plugins: [pinia, router],
      stubs: {
        Toast: { template: '<div />' },
        ConfirmDialog: { template: '<div />' },
        PcAppShell: { template: '<div />' },
        MobileAppShell: { template: '<div />' },
      },
    },
  })

  return { wrapper, router }
}

describe('App theme handling on standalone routes', () => {
  beforeEach(() => {
    localStorage.clear()
    document.documentElement.classList.remove('dark')
  })

  afterEach(() => {
    vi.restoreAllMocks()
    window.matchMedia = originalMatchMedia
    localStorage.clear()
    document.documentElement.classList.remove('dark')
  })

  it('disables the document dark theme on a standalone player route and restores it on a normal route', async () => {
    const mediaQuery = createMediaQuery(true)
    window.matchMedia = vi.fn(() => mediaQuery.query)

    const { wrapper, router } = await mountApp('/media/play/1')
    await flushPromises()

    // 首次直达独立播放路由：主题在渲染期间已被禁用
    expect(document.documentElement.classList.contains('dark')).toBe(false)

    // 离开播放页后恢复主题
    await router.push('/files')
    await flushPromises()
    expect(document.documentElement.classList.contains('dark')).toBe(true)

    wrapper.unmount()
  })
})
