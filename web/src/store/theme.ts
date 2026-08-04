import { computed, ref } from 'vue'
import { defineStore } from 'pinia'

export type ThemeMode = 'system' | 'light' | 'dark'

export const THEME_STORAGE_KEY = 'jcloud_theme_mode'

const THEME_MEDIA_QUERY = '(prefers-color-scheme: dark)'

function isThemeMode(value: string | null): value is ThemeMode {
  return value === 'system' || value === 'light' || value === 'dark'
}

function readStoredMode(): ThemeMode {
  try {
    const stored = localStorage.getItem(THEME_STORAGE_KEY)
    return isThemeMode(stored) ? stored : 'system'
  } catch {
    return 'system'
  }
}

function applyDocumentTheme(isDark: boolean) {
  if (typeof document !== 'undefined') {
    document.documentElement.classList.toggle('dark', isDark)
  }
}

export const useThemeStore = defineStore('theme', () => {
  const mode = ref<ThemeMode>('system')
  const systemDark = ref(false)
  const themeEnabled = ref(true)
  let mediaQuery: MediaQueryList | null = null

  const isDark = computed(() => mode.value === 'dark' || (mode.value === 'system' && systemDark.value))

  function applyTheme() {
    applyDocumentTheme(themeEnabled.value && isDark.value)
  }

  function handleSystemThemeChange(event: MediaQueryListEvent) {
    systemDark.value = event.matches
    if (mode.value === 'system') {
      applyTheme()
    }
  }

  function attachMediaQueryListener() {
    if (mediaQuery) {
      return
    }

    try {
      mediaQuery = window.matchMedia(THEME_MEDIA_QUERY)
      systemDark.value = mediaQuery.matches
      if (mediaQuery.addEventListener) {
        mediaQuery.addEventListener('change', handleSystemThemeChange)
      } else {
        mediaQuery.addListener(handleSystemThemeChange)
      }
    } catch {
      mediaQuery = null
      systemDark.value = false
    }
  }

  function persistMode() {
    try {
      localStorage.setItem(THEME_STORAGE_KEY, mode.value)
    } catch {
      // Theme state remains available for the current session when storage is unavailable.
    }
  }

  function setMode(nextMode: ThemeMode) {
    mode.value = nextMode
    if (nextMode === 'system') {
      attachMediaQueryListener()
    }
    persistMode()
    applyTheme()
  }

  function cycleMode() {
    const nextMode: ThemeMode = mode.value === 'system'
      ? 'light'
      : mode.value === 'light'
        ? 'dark'
        : 'system'
    setMode(nextMode)
  }

  function initialize() {
    mode.value = readStoredMode()
    persistMode()
    if (mode.value === 'system') {
      attachMediaQueryListener()
    }
    applyTheme()
  }

  function setThemeEnabled(enabled: boolean) {
    themeEnabled.value = enabled
    applyTheme()
  }

  initialize()

  return {
    mode,
    isDark,
    cycleMode,
    initialize,
    setThemeEnabled,
  }
})
