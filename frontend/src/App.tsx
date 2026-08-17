import { Suspense, lazy, type ComponentType } from 'react'
import { Routes, Route, Navigate, useLocation } from 'react-router'
import { useAuthStore } from '@/stores/auth-store'

// —— 同步加载（登录关键路径：体积小、无重依赖，避免登录页二次闪屏）——
import { AuthShell } from '@/pages/auth/auth-shell'
import LoginPage from '@/pages/auth/login-page'
import RegisterPage from '@/pages/auth/register-page'
import { PermissionGuard } from '@/components/guards/permission-guard'
import { RouteSkeleton } from '@/components/common/route-skeleton'
import { ROLE } from '@/lib/constants'

// —— 懒加载 shells（仅 /app、/admin 路由需要，从登录页加载链路中剥离）——
const AppShell = lazy(() => import('@/pages/app/app-shell').then((m) => ({ default: m.AppShell })))
const AdminShell = lazy(() => import('@/pages/admin/admin-shell').then((m) => ({ default: m.AdminShell })))

// —— 懒加载业务页（重依赖 chat/markdown/shiki/katex、echarts 仅在这些页才加载）——
const ChatPage = lazy(() => import('@/pages/app/chat-page'))
const KnowledgePage = lazy(() => import('@/pages/app/knowledge-page'))
const TeamsPage = lazy(() => import('@/pages/app/teams-page'))
const TeamDetailPage = lazy(() => import('@/pages/app/team-detail-page'))
const UsagePage = lazy(() => import('@/pages/app/usage-page'))
const ModelsPage = lazy(() => import('@/pages/app/models-page'))
const AccountPage = lazy(() => import('@/pages/app/account-page'))

const PromptsPage = lazy(() => import('@/pages/admin/prompts-page'))
const UsersPage = lazy(() => import('@/pages/admin/users-page'))
const RolesPage = lazy(() => import('@/pages/admin/roles-page'))
const EvaluationPage = lazy(() => import('@/pages/admin/evaluation-page'))

const NotFoundPage = lazy(() => import('@/pages/error/not-found-page'))
const ForbiddenPage = lazy(() => import('@/pages/error/forbidden-page'))
const ServerErrorPage = lazy(() => import('@/pages/error/server-error-page'))

/** 把懒加载组件包进 Suspense（嵌套路由下父级 shell 一旦加载即常驻，仅内容区显示骨架）。 */
function lazyEl(C: ComponentType) {
  return (
    <Suspense fallback={<RouteSkeleton />}>
      <C />
    </Suspense>
  )
}

/** 根路径着陆分流（IA §4.2）。
 *  响应式读 store（FE-008）：等 /me 兜底完成（initialized）再分流，
 *  避免已登录用户在 /me 返回前被误判为未登录而闪现登录页。 */
function LandingRedirect() {
  const user = useAuthStore((s) => s.user)
  const initialized = useAuthStore((s) => s.initialized)
  if (!initialized) return <RouteSkeleton />
  if (!user) return <Navigate to="/auth/login" replace />
  const isAdmin = user.roles?.includes(ROLE.ADMIN)
  return <Navigate to={isAdmin ? '/admin' : '/app/chat'} replace />
}

/** 已登录访问 /auth/* → 回着陆（响应式读 store，FE-008） */
function AuthRedirect() {
  const user = useAuthStore((s) => s.user)
  if (user) {
    const isAdmin = user.roles?.includes(ROLE.ADMIN)
    return <Navigate to={isAdmin ? '/admin' : '/app/chat'} replace />
  }
  return <AuthShell />
}

/** 后台 index → 首个有权限的后台子页（IA §4.1） */
function AdminIndex() {
  const { pathname } = useLocation()
  return <Navigate to={`${pathname}/prompts`} replace />
}

export function App() {
  return (
    <Routes>
      {/* 根着陆 */}
      <Route path="/" element={<LandingRedirect />} />

      {/* 认证（同步加载，无闪屏） */}
      <Route path="/auth" element={<AuthRedirect />}>
        <Route index element={<Navigate to="/auth/login" replace />} />
        <Route path="login" element={<LoginPage />} />
        <Route path="register" element={<RegisterPage />} />
      </Route>

      {/* 前台 */}
      <Route path="/app" element={lazyEl(AppShell)}>
        <Route index element={<Navigate to="/app/chat" replace />} />
        <Route path="chat" element={lazyEl(ChatPage)} />
        <Route path="chat/:conversationId" element={lazyEl(ChatPage)} />
        <Route path="knowledge" element={<Navigate to="/app/knowledge/personal" replace />} />
        <Route path="knowledge/personal" element={lazyEl(KnowledgePage)} />
        <Route path="knowledge/team/:teamId" element={lazyEl(KnowledgePage)} />
        <Route path="teams" element={lazyEl(TeamsPage)} />
        <Route path="teams/:teamId" element={lazyEl(TeamDetailPage)} />
        <Route path="usage" element={<PermissionGuard require="usage:view">{lazyEl(UsagePage)}</PermissionGuard>} />
        {/* 模型配置：预留接口，仅 ADMIN（IA §2.3 v0.3.0 收回 USER） */}
        <Route
          path="models"
          element={
            <PermissionGuard require="model:config">
              {lazyEl(ModelsPage)}
            </PermissionGuard>
          }
        />
        <Route path="account" element={lazyEl(AccountPage)} />
      </Route>

      {/* 后台 */}
      <Route path="/admin" element={lazyEl(AdminShell)}>
        <Route index element={<AdminIndex />} />
        <Route path="prompts" element={<PermissionGuard require="prompt:manage">{lazyEl(PromptsPage)}</PermissionGuard>} />
        <Route path="users" element={<PermissionGuard require="user:manage">{lazyEl(UsersPage)}</PermissionGuard>} />
        <Route path="roles" element={<PermissionGuard require="role:manage">{lazyEl(RolesPage)}</PermissionGuard>} />
        <Route path="evaluation" element={<PermissionGuard require="evaluation:manage">{lazyEl(EvaluationPage)}</PermissionGuard>} />
      </Route>

      {/* 错误页 */}
      <Route path="/403" element={lazyEl(ForbiddenPage)} />
      <Route path="/500" element={lazyEl(ServerErrorPage)} />
      <Route path="*" element={lazyEl(NotFoundPage)} />
    </Routes>
  )
}
