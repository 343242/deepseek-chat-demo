import { Routes, Route, Navigate, useLocation } from 'react-router'
import { useAuthStore } from '@/stores/auth-store'

import { AuthShell } from '@/pages/auth/auth-shell'
import { AppShell } from '@/pages/app/app-shell'
import { AdminShell } from '@/pages/admin/admin-shell'

import LoginPage from '@/pages/auth/login-page'
import RegisterPage from '@/pages/auth/register-page'

import ChatPage from '@/pages/app/chat-page'
import KnowledgePage from '@/pages/app/knowledge-page'
import TeamsPage from '@/pages/app/teams-page'
import TeamDetailPage from '@/pages/app/team-detail-page'
import UsagePage from '@/pages/app/usage-page'
import ModelsPage from '@/pages/app/models-page'
import AccountPage from '@/pages/app/account-page'

import PromptsPage from '@/pages/admin/prompts-page'
import UsersPage from '@/pages/admin/users-page'
import RolesPage from '@/pages/admin/roles-page'
import EvaluationPage from '@/pages/admin/evaluation-page'

import NotFoundPage from '@/pages/error/not-found-page'
import ForbiddenPage from '@/pages/error/forbidden-page'
import ServerErrorPage from '@/pages/error/server-error-page'

import { PermissionGuard } from '@/components/guards/permission-guard'
import { ROLE } from '@/lib/constants'

/** 根路径着陆分流（IA §4.2） */
function LandingRedirect() {
  const user = useAuthStore.getState().user
  if (!user) return <Navigate to="/auth/login" replace />
  const isAdmin = user.roles?.includes(ROLE.ADMIN)
  return <Navigate to={isAdmin ? '/admin' : '/app/chat'} replace />
}

/** 已登录访问 /auth/* → 回着陆 */
function AuthRedirect() {
  const user = useAuthStore.getState().user
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

      {/* 认证 */}
      <Route path="/auth" element={<AuthRedirect />}>
        <Route index element={<Navigate to="/auth/login" replace />} />
        <Route path="login" element={<LoginPage />} />
        <Route path="register" element={<RegisterPage />} />
      </Route>

      {/* 前台 */}
      <Route path="/app" element={<AppShell />}>
        <Route index element={<Navigate to="/app/chat" replace />} />
        <Route path="chat" element={<ChatPage />} />
        <Route path="chat/:conversationId" element={<ChatPage />} />
        <Route path="knowledge" element={<Navigate to="/app/knowledge/personal" replace />} />
        <Route path="knowledge/personal" element={<KnowledgePage />} />
        <Route path="knowledge/team/:teamId" element={<KnowledgePage />} />
        <Route path="teams" element={<TeamsPage />} />
        <Route path="teams/:teamId" element={<TeamDetailPage />} />
        <Route path="usage" element={<UsagePage />} />
        {/* 模型配置：预留接口，仅 ADMIN（IA §2.3 v0.3.0 收回 USER） */}
        <Route
          path="models"
          element={
            <PermissionGuard require="model:config">
              <ModelsPage />
            </PermissionGuard>
          }
        />
        <Route path="account" element={<AccountPage />} />
      </Route>

      {/* 后台 */}
      <Route path="/admin" element={<AdminShell />}>
        <Route index element={<AdminIndex />} />
        <Route path="prompts" element={<PermissionGuard require="prompt:manage"><PromptsPage /></PermissionGuard>} />
        <Route path="users" element={<PermissionGuard require="user:manage"><UsersPage /></PermissionGuard>} />
        <Route path="roles" element={<PermissionGuard require="role:manage"><RolesPage /></PermissionGuard>} />
        <Route path="evaluation" element={<PermissionGuard require="evaluation:manage"><EvaluationPage /></PermissionGuard>} />
      </Route>

      {/* 错误页 */}
      <Route path="/403" element={<ForbiddenPage />} />
      <Route path="/500" element={<ServerErrorPage />} />
      <Route path="*" element={<NotFoundPage />} />
    </Routes>
  )
}
