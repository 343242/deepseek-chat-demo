import { create } from 'zustand'
import { persist } from 'zustand/middleware'
import { STORAGE_KEYS } from '@/lib/constants'

export type ThemeMode = 'light' | 'dark' | 'system'

interface ThemeState {
  mode: ThemeMode
  setMode: (m: ThemeMode) => void
  toggle: () => void
}

function systemPrefersDark(): boolean {
  return typeof window !== 'undefined' && window.matchMedia('(prefers-color-scheme: dark)').matches
}

/** 当前实际生效主题（解析 system） */
export function resolvedTheme(mode: ThemeMode): 'light' | 'dark' {
  if (mode === 'system') return systemPrefersDark() ? 'dark' : 'light'
  return mode
}

/** 应用 .dark 类到 <html> */
export function applyTheme(mode: ThemeMode) {
  const dark = resolvedTheme(mode) === 'dark'
  document.documentElement.classList.toggle('dark', dark)
}

export const useThemeStore = create<ThemeState>()(
  persist(
    (set, get) => ({
      mode: 'system',
      setMode: (mode) => {
        applyTheme(mode)
        set({ mode })
      },
      toggle: () => {
        const current = resolvedTheme(get().mode)
        const next: ThemeMode = current === 'dark' ? 'light' : 'dark'
        get().setMode(next)
      },
    }),
    {
      name: STORAGE_KEYS.theme,
      // 仅持久化模式
      partialize: (s) => ({ mode: s.mode }),
      // hydrate 后应用
      onRehydrateStorage: () => (state) => {
        if (state) applyTheme(state.mode)
      },
    },
  ),
)

/** 监听系统主题变化（mode=system 时联动） */
if (typeof window !== 'undefined') {
  window.matchMedia('(prefers-color-scheme: dark)').addEventListener('change', () => {
    const { mode } = useThemeStore.getState()
    if (mode === 'system') applyTheme('system')
  })
}
