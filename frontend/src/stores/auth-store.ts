import { create } from 'zustand'
import type { UserInfo } from '@/types/auth'

interface AuthState {
  user: UserInfo | null
  /** 权限码列表（来自 /api/auth/me，缓存供权限守卫读取） */
  permissions: string[]
  /** 是否已加载过 /me（避免未登录闪烁登录页） */
  initialized: boolean
  setUser: (user: UserInfo | null) => void
  setInitialized: (v: boolean) => void
  clear: () => void
}

export const useAuthStore = create<AuthState>((set) => ({
  user: null,
  permissions: [],
  initialized: false,
  setUser: (user) =>
    set({ user, permissions: user?.permissions ?? [], initialized: true }),
  setInitialized: (v) => set({ initialized: v }),
  clear: () => set({ user: null, permissions: [], initialized: true }),
}))

export const isAuthenticated = () => useAuthStore.getState().user !== null
