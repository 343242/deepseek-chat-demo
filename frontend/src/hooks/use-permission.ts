import { useAuthStore } from '@/stores/auth-store'
import { ADMIN_PERMISSIONS, PERMISSION } from '@/lib/constants'
import { ROLE } from '@/lib/constants'

/** 权限守卫 hook（IA §5.3） */
export function usePermission() {
  const permissions = useAuthStore((s) => s.permissions)
  const user = useAuthStore((s) => s.user)

  const has = (code: string): boolean => permissions.includes(code)
  const hasAny = (codes: readonly string[] | string[]): boolean => codes.some((c) => permissions.includes(c))

  return {
    permissions,
    has,
    hasAny,
    /** 是否拥有任一后台管理权限（TopBar 后台切换钮判定，IA §2.3） */
    hasAnyAdminPermission: () => hasAny(ADMIN_PERMISSIONS),
    /** 是否 ADMIN 角色 */
    isAdmin: () => user?.roles?.includes(ROLE.ADMIN) ?? false,
  }
}

/** PermissionGuard 便捷判定（非 hook 场景） */
export function checkPermission(code: string, permissions: string[]): boolean {
  return permissions.includes(code)
}

export { PERMISSION }
