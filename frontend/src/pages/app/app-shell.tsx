import { Outlet, useLocation } from 'react-router'
import { TopBar } from '@/components/shell/top-bar'
import { AppSidebar } from '@/components/shell/app-sidebar'
import { RequireAuth } from '@/components/guards/require-auth'
import { ConversationList } from '@/components/chat/conversation-list'
import { PermissionGuard } from '@/components/guards/permission-guard'
import { PERMISSION } from '@/lib/constants'

/**
 * 前台 AppShell（IA §3.1 形态 2）—— TopBar + 280px 左栏（纵向堆叠）+ 内容
 * 左栏中段会话列表仅在聊天页显示（IA §3.1）
 */
export function AppShell() {
  const { pathname } = useLocation()
  const isChat = pathname.startsWith('/app/chat')

  return (
    <RequireAuth>
      <PermissionGuard require={PERMISSION.CHAT_SEND} redirect="/app/account">
        <div className="flex h-screen flex-col bg-canvas">
          <TopBar shell="app" />
          <div className="flex min-h-0 flex-1">
            <AppSidebar conversationList={isChat ? <ConversationList /> : undefined} />
            <main className="flex min-h-0 flex-1 flex-col overflow-hidden">
              <Outlet />
            </main>
          </div>
        </div>
      </PermissionGuard>
    </RequireAuth>
  )
}
