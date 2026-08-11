import { useEffect } from 'react'
import { useNavigate, useLocation } from 'react-router'
import { useMe } from '@/api/auth'
import { useAuthStore } from '@/stores/auth-store'
import { useThemeStore, applyTheme } from '@/stores/theme-store'

/**
 * 应用启动加载器（IA-5 权限兜底）：
 * - 挂载即调 GET /api/auth/me 拉取当前用户 + 权限快照写入 store
 * - 监听 api-fetch 派发的 'srag:unauthorized'（401 refresh 失败）→ 跳登录
 * - 应用持久化的主题
 */
export function AppDataLoader({ children }: { children: React.ReactNode }) {
  const { pathname } = useLocation()
  // 登录/注册页不预取 /me（避免未认证 401→refresh 级联报错）
  const isAuthRoute = pathname.startsWith('/auth')
  const me = useMe({ enabled: !isAuthRoute })
  const setInitialized = useAuthStore((s) => s.setInitialized)
  const navigate = useNavigate()

  // 主题：挂载时应用（zustand persist 已在 storage 层 apply，这里兜底）
  useEffect(() => {
    applyTheme(useThemeStore.getState().mode)
  }, [])

  // /me 完成后标记 initialized（成功 setUser 已在 queryFn 内；失败也标记，避免无限 loader）
  useEffect(() => {
    if (me.isSuccess || me.isError) setInitialized(true)
  }, [me.isSuccess, me.isError, setInitialized])

  // auth 路由直接标记 initialized（无需 /me，避免 RequireAuth/loader 误判）
  useEffect(() => {
    if (isAuthRoute) setInitialized(true)
  }, [isAuthRoute, setInitialized])

  // 401 全局跳转
  useEffect(() => {
    const handler = () => {
      useAuthStore.getState().clear()
      const redirect = encodeURIComponent(window.location.pathname + window.location.search)
      navigate(`/auth/login?redirect=${redirect}`, { replace: true })
    }
    window.addEventListener('srag:unauthorized', handler)
    return () => window.removeEventListener('srag:unauthorized', handler)
  }, [navigate])

  return <>{children}</>
}
