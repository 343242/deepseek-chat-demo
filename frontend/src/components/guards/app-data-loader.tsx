import { useEffect } from 'react'
import { useNavigate, useLocation } from 'react-router'
import { fetchMe } from '@/api/auth'
import { useAuthStore } from '@/stores/auth-store'
import { useThemeStore, applyTheme } from '@/stores/theme-store'
import type { UserInfo } from '@/types/auth'

/** 启动 /me 的 in-flight 单例：StrictMode dev 下 effect 双跑共享同一请求 */
let bootMePromise: Promise<UserInfo> | null = null

/**
 * 应用启动加载器（IA-5 权限兜底）：
 * - 非 auth 路由启动时拉一次 GET /api/auth/me，建立会话快照写入 store
 *   （useAuthStore 是会话用户唯一归属，写入只发生在事件边界：
 *   启动装载、登录/注册成功、登出/401 清除——不经 Query 缓存中转）
 * - 监听 api-fetch 派发的 'srag:unauthorized'（401 refresh 失败）→ 跳登录
 * - 应用持久化的主题
 */
export function AppDataLoader({ children }: { children: React.ReactNode }) {
  const { pathname } = useLocation()
  // 登录/注册页不预取 /me（避免未认证 401→refresh 级联报错）
  const isAuthRoute = pathname.startsWith('/auth')
  const navigate = useNavigate()

  // 启动装载：拉 /me → setUser（setUser 自带 initialized=true，RequireAuth 即刻放行）。
  // 失败（未登录/网络异常）也放行 initialized，由守卫按 user=null 分流登录页，避免无限骨架。
  useEffect(() => {
    if (isAuthRoute) {
      useAuthStore.getState().setInitialized(true)
      return
    }
    if (useAuthStore.getState().user) return // 本会话已有快照（登录/注册直写后跳转回来）
    let alive = true
    bootMePromise ??= fetchMe().finally(() => {
      bootMePromise = null
    })
    void bootMePromise
      .then((me) => {
        if (alive) useAuthStore.getState().setUser(me)
      })
      .catch(() => {
        if (alive) useAuthStore.getState().setInitialized(true)
      })
    return () => {
      alive = false
    }
  }, [isAuthRoute])

  // 主题：挂载时应用（zustand persist 已在 storage 层 apply，这里兜底）
  useEffect(() => {
    applyTheme(useThemeStore.getState().mode)
  }, [])

  // 401 全局跳转（唯一登出通道，见 data-and-state.md 认证时序不变量）
  useEffect(() => {
    const handler = () => {
      useAuthStore.getState().clear()
      const redirect = encodeURIComponent(window.location.pathname + window.location.search)
      void navigate(`/auth/login?redirect=${redirect}`, { replace: true })
    }
    window.addEventListener('srag:unauthorized', handler)
    return () => window.removeEventListener('srag:unauthorized', handler)
  }, [navigate])

  return <>{children}</>
}
