import { useAuthStore } from '@/stores/auth-store'
import { usePermission } from './use-permission'

/** 认证状态便捷 hook（聚合 user + 权限判定） */
export function useAuth() {
  const user = useAuthStore((s) => s.user)
  const initialized = useAuthStore((s) => s.initialized)
  const setUser = useAuthStore((s) => s.setUser)
  const clear = useAuthStore((s) => s.clear)
  const perm = usePermission()

  return {
    user,
    initialized,
    isAuthenticated: user !== null,
    username: user?.nickname || user?.username || '',
    initials: (user?.nickname || user?.username || '?').slice(0, 1).toUpperCase(),
    setUser,
    clear,
    ...perm,
  }
}
