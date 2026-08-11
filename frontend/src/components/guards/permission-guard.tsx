import { type ReactNode } from 'react'
import { Navigate, useLocation } from 'react-router'
import { usePermission } from '@/hooks/use-permission'

/**
 * PermissionGuard（IA §5.3）—— 路由级 + 组件级权限守卫
 * - require 权限码（数组=满足任一）
 * - feature feature flag 名（当前仅 evaluation，IA-1）
 * - fallback 无权限替代渲染（默认 null 即隐藏，组件级用）
 * - redirect 路由级无权限重定向（默认 /app/chat）
 */
export interface PermissionGuardProps {
  require?: string | string[]
  feature?: string
  fallback?: ReactNode
  redirect?: string
  children: ReactNode
}

export function PermissionGuard({ require, feature, fallback = null, redirect = '/app/chat', children }: PermissionGuardProps) {
  const { hasAny } = usePermission()
  const location = useLocation()

  const codes = require ? (Array.isArray(require) ? require : [require]) : []
  const okPerm = codes.length === 0 || hasAny(codes)
  // feature flag：evaluation 当前无 /me 字段，默认放行（仅权限码判定，IA-1）
  const okFeature = !feature || feature !== 'evaluation' || true

  if (okPerm && okFeature) return <>{children}</>

  // 路由级（有 redirect）→ 重定向；组件级（fallback）→ 隐藏
  if (redirect && !fallback) {
    return <Navigate to={redirect} replace state={{ from: location.pathname }} />
  }
  return <>{fallback}</>
}
