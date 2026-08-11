import { Outlet, Navigate } from 'react-router'
import { TopBar } from '@/components/shell/top-bar'
import { AdminSidebar } from '@/components/shell/admin-sidebar'
import { RequireAuth } from '@/components/guards/require-auth'
import { usePermission } from '@/hooks/use-permission'

/** 后台 AdminShell（IA §3.2）—— 仅拥有任一后台管理权限可进；否则静默回前台 */
export function AdminShell() {
  const { hasAnyAdminPermission } = usePermission()

  return (
    <RequireAuth>
      {!hasAnyAdminPermission() ? (
        <Navigate to="/app/chat" replace />
      ) : (
        <div className="flex h-screen flex-col bg-canvas">
          <TopBar shell="admin" />
          <div className="flex min-h-0 flex-1">
            <AdminSidebar />
            <main className="flex min-h-0 flex-1 flex-col overflow-y-auto">
              <Outlet />
            </main>
          </div>
        </div>
      )}
    </RequireAuth>
  )
}
