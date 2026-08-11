import { Navigate, useLocation } from 'react-router'
import { useAuthStore } from '@/stores/auth-store'
import { Loader2 } from 'lucide-react'

/**
 * 认证守卫：未登录 → /auth/login?redirect=<原URL>
 * 未完成 /me 加载时显示全屏骨架，避免未登录闪烁
 */
export function RequireAuth({ children }: { children: React.ReactNode }) {
  const user = useAuthStore((s) => s.user)
  const initialized = useAuthStore((s) => s.initialized)
  const location = useLocation()

  // /me 尚未返回：显示加载态（AppDataLoader 在外层驱动 /me）
  if (!initialized && !user) {
    return (
      <div className="flex h-screen items-center justify-center bg-canvas">
        <Loader2 className="size-6 animate-spin text-primary-600" />
      </div>
    )
  }

  if (!user) {
    const redirect = encodeURIComponent(location.pathname + location.search)
    return <Navigate to={`/auth/login?redirect=${redirect}`} replace />
  }

  return <>{children}</>
}
