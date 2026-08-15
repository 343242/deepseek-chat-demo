import { useNavigate } from 'react-router'
import { ArrowLeft, ScrollText, UserCog, ShieldCheck, FlaskConical, PanelLeftClose, PanelLeft } from 'lucide-react'
import { NavItem } from './nav-item'
import { Separator } from '@/components/ui/separator'
import { Button } from '@/components/ui/button'
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip'
import { usePermission } from '@/hooks/use-permission'
import { PERMISSION } from '@/lib/constants'
import { useUiStore } from '@/stores/ui-store'
import { cn } from '@/lib/utils'

/**
 * 后台侧栏（IA §3.2）—— 240px / 64px 可折叠
 * 单栏 + 抽屉详情为主；评估项受 evaluation:manage + feature 门控
 */
export function AdminSidebar() {
  const { has } = usePermission()
  const collapsed = useUiStore((s) => s.adminSidebarCollapsed)
  const toggle = useUiStore((s) => s.toggleAdminSidebar)
  const navigate = useNavigate()

  return (
    <aside className={cn('flex shrink-0 flex-col border-r border-line bg-surface transition-[width] duration-200', collapsed ? 'w-16' : 'w-60')}>
      {/* 返回前台（顶部固定） */}
      <div className="p-3">
        <button
          onClick={() => void navigate('/app/chat')}
          className={cn(
            'flex items-center gap-2.5 rounded-md px-3 py-2 text-sm text-muted transition-colors hover:bg-hover hover:text-fg focus-visible:shadow-focus',
            collapsed && 'mx-auto justify-center w-10 h-10',
          )}
          title={collapsed ? '返回前台' : undefined}
        >
          <ArrowLeft className="size-4" />
          {!collapsed && <span>返回前台</span>}
        </button>
      </div>

      <Separator />

      <nav className="flex flex-1 flex-col gap-0.5 p-3">
        {has(PERMISSION.PROMPT_MANAGE) && (
          <NavItem to="/admin/prompts" label="系统提示词" icon={ScrollText} collapsed={collapsed} />
        )}
        {has(PERMISSION.USER_MANAGE) && (
          <NavItem to="/admin/users" label="用户管理" icon={UserCog} collapsed={collapsed} />
        )}
        {has(PERMISSION.ROLE_MANAGE) && (
          <NavItem to="/admin/roles" label="角色权限" icon={ShieldCheck} collapsed={collapsed} />
        )}
        {/* 评估：feature flag 当前无 /me 字段，按权限码显示入口（IA-1，非评估环境点击 404） */}
        {has(PERMISSION.EVALUATION_MANAGE) && (
          <NavItem to="/admin/evaluation" label="评估" icon={FlaskConical} collapsed={collapsed} />
        )}
      </nav>

      <Separator />

      {/* 折叠按钮 */}
      <div className="p-3">
        <Tooltip>
          <TooltipTrigger asChild>
            <Button variant="ghost" size="sm" onClick={toggle} className={cn('w-full', collapsed && 'px-0')}>
              {collapsed ? <PanelLeft className="size-4" /> : <><PanelLeftClose className="size-4" /> 收起</>}
            </Button>
          </TooltipTrigger>
          {collapsed && <TooltipContent side="right">展开侧栏</TooltipContent>}
        </Tooltip>
      </div>
    </aside>
  )
}
