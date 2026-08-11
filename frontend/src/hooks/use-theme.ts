import { useThemeStore, type ThemeMode, resolvedTheme } from '@/stores/theme-store'

export function useTheme() {
  const mode = useThemeStore((s) => s.mode)
  const setMode = useThemeStore((s) => s.setMode)
  const toggle = useThemeStore((s) => s.toggle)
  return {
    mode,
    resolved: resolvedTheme(mode),
    setMode,
    toggle,
  } as const
}

export type { ThemeMode }
