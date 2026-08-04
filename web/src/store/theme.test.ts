import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { THEME_STORAGE_KEY, useThemeStore } from './theme'

type MediaQueryListener = (event: MediaQueryListEvent) => void

function createMediaQuery(initialMatches: boolean) {
  let matches = initialMatches
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

  return {
    query,
    setMatches(nextMatches: boolean) {
      matches = nextMatches
      const event = { matches, media: query.media } as MediaQueryListEvent
      listeners.forEach((listener) => listener(event))
    },
  }
}

const originalMatchMedia = window.matchMedia

describe('themeStore', () => {
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

  it('defaults to system mode and resolves the effective dark state', () => {
    const mediaQuery = createMediaQuery(true)
    window.matchMedia = vi.fn(() => mediaQuery.query)
    setActivePinia(createPinia())

    const themeStore = useThemeStore()

    expect(themeStore.mode).toBe('system')
    expect(themeStore.isDark).toBe(true)
    expect(document.documentElement.classList.contains('dark')).toBe(true)
  })

  it('cycles through system, light, and dark in order', () => {
    const mediaQuery = createMediaQuery(false)
    window.matchMedia = vi.fn(() => mediaQuery.query)
    setActivePinia(createPinia())
    const themeStore = useThemeStore()

    themeStore.cycleMode()
    expect(themeStore.mode).toBe('light')
    themeStore.cycleMode()
    expect(themeStore.mode).toBe('dark')
    themeStore.cycleMode()
    expect(themeStore.mode).toBe('system')
  })

  it('persists and restores a valid mode', () => {
    const mediaQuery = createMediaQuery(false)
    window.matchMedia = vi.fn(() => mediaQuery.query)
    setActivePinia(createPinia())
    const themeStore = useThemeStore()

    themeStore.cycleMode()

    expect(localStorage.getItem(THEME_STORAGE_KEY)).toBe('light')

    setActivePinia(createPinia())
    expect(useThemeStore().mode).toBe('light')
  })

  it('falls back to system mode for an invalid persisted value', () => {
    localStorage.setItem(THEME_STORAGE_KEY, 'sepia')
    const mediaQuery = createMediaQuery(false)
    window.matchMedia = vi.fn(() => mediaQuery.query)
    setActivePinia(createPinia())

    expect(useThemeStore().mode).toBe('system')
    expect(localStorage.getItem(THEME_STORAGE_KEY)).toBe('system')
  })

  it('responds to media changes only in system mode', () => {
    const mediaQuery = createMediaQuery(false)
    window.matchMedia = vi.fn(() => mediaQuery.query)
    setActivePinia(createPinia())
    const themeStore = useThemeStore()

    mediaQuery.setMatches(true)
    expect(themeStore.isDark).toBe(true)

    themeStore.cycleMode()
    mediaQuery.setMatches(false)
    expect(themeStore.mode).toBe('light')
    expect(themeStore.isDark).toBe(false)

    themeStore.cycleMode()
    mediaQuery.setMatches(false)
    expect(themeStore.mode).toBe('dark')
    expect(themeStore.isDark).toBe(true)
  })

  it('removes the document theme while the player route is active', () => {
    const mediaQuery = createMediaQuery(true)
    window.matchMedia = vi.fn(() => mediaQuery.query)
    setActivePinia(createPinia())
    const themeStore = useThemeStore()

    expect(document.documentElement.classList.contains('dark')).toBe(true)
    themeStore.setThemeEnabled(false)
    expect(document.documentElement.classList.contains('dark')).toBe(false)

    themeStore.setThemeEnabled(true)
    expect(document.documentElement.classList.contains('dark')).toBe(true)
  })
})
