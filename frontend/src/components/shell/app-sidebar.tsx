import { useNavigate } from 'react-router'
import { MessageSquare, BookOpen, Users, BarChart3, Settings } from 'lucide-react'
import { NavItem } from './nav-item'
import { Separator } from '@/components/ui/separator'
import { Avatar, AvatarFallback } from '@/components/ui/avatar'
import { useAuth } from '@/hooks/use-auth'
import { usePermission } from '@/hooks/use-permission'
import { PERMISSION } from '@/lib/constants'

/**
 * 前台侧栏（IA §3.1 形态 2）—— 固定 280px，不折叠
 * 三区：主导航（上·固定）/ 会话列表（中·仅聊天页·独立滚动）/ 用户（下·固定）
 */
export function AppSidebar({ conversationList }: { conversationList?: React.ReactNode }) {
  const { has, hasAnyAdminPermission, initials, username } = useAuth()
  const navigate = useNavigate()

  return (
    <aside className="flex w-[280px] shrink-0 flex-col border-r border-line bg-surface">
      {/* 上区：主导航（固定不滚） */}
      <nav className="flex flex-col gap-0.5 p-3">
        {has(PERMISSION.CHAT_SEND) && <NavItem to="/app/chat" label="聊天" icon={MessageSquare} activePrefix="/app/chat" />}
        <NavItem to="/app/knowledge" label="知识库" icon={BookOpen} activePrefix="/app/knowledge" />
        <NavItem to="/app/teams" label="团队" icon={Users} activePrefix="/app/teams" />
        {has(PERMISSION.USAGE_VIEW) && <NavItem to="/app/usage" label="用量统计" icon={BarChart3} />}
        {hasAnyAdminPermission() && (
          <>
            <Separator className="my-1.5" />
            <NavItem to="/admin" label="后台管理" icon={Settings} />
          </>
        )}
      </nav>

      <Separator />

      {/* 中区：会话列表（仅聊天页传入，独立滚动；否则诚实留白） */}
      {conversationList ? (
        <div className="flex min-h-0 flex-1 flex-col">{conversationList}</div>
      ) : (
        <div className="min-h-0 flex-1" />
      )}

      <Separator />

      {/* 下区：用户身份（点击进账号设置；完整菜单在 TopBar） */}
      <button
        onClick={() => void navigate('/app/account')}
        className="flex items-center gap-2.5 px-3 py-2.5 text-left transition-colors hover:bg-hover focus-visible:shadow-focus"
      >
        <Avatar className="size-7">
          <AvatarFallback className="text-xs">{initials}</AvatarFallback>
        </Avatar>
        <span className="flex-1 truncate text-sm text-fg">{username}</span>
      </button>
    </aside>
  )
}

export { usePermission }
