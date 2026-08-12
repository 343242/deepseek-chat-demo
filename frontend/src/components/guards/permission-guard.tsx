import { type ReactNode } from 'react'
import { Navigate, useLocation } from 'react-router'
import { usePermission } from '@/hooks/use-permission'

/**
 * PermissionGuard（IA §5.3）—— 路由级 + 组件级权限守卫
 * - require 权限码（数组=满足任一）
 * - fallback 无权限替代渲染（默认 null 即隐藏，组件级用）
 * - redirect 路由级无权限重定向（默认 /app/chat）
 *
 * 注：原 `feature` 门控（恒真死代码）已删除；待真 feature flag 系统就绪（IA-1 evaluation）
 * 再以 `useFeatureFlag(feature)` 形式接回，避免占位代码误判。
 */
export interface PermissionGuardProps {
  require?: string | string[]
  fallback?: ReactNode
  redirect?: string
  children: ReactNode
}

export function PermissionGuard({ require, fallback = null, redirect = '/app/chat', children }: PermissionGuardProps) {
  const { hasAny } = usePermission()
  const location = useLocation()

  const codes = require ? (Array.isArray(require) ? require : [require]) : []
  const okPerm = codes.length === 0 || hasAny(codes)

  if (okPerm) return <>{children}</>

  // 路由级（有 redirect）→ 重定向；组件级（fallback）→ 隐藏
  if (redirect && !fallback) {
    return <Navigate to={redirect} replace state={{ from: location.pathname }} />
  }
  return <>{fallback}</>
}
