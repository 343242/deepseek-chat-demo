import { Link, useLocation } from 'react-router'
import type { LucideIcon } from 'lucide-react'
import { cn } from '@/lib/utils'

/** 导航项（DS §5.1）：默认/hover/激活态，激活 3px brand-600 左指示条 */
export interface NavItemProps {
  to: string
  label: string
  icon: LucideIcon
  /** 路由前缀匹配（默认用 to） */
  activePrefix?: string
  collapsed?: boolean
  badge?: React.ReactNode
}

export function NavItem({ to, label, icon: Icon, activePrefix, collapsed, badge }: NavItemProps) {
  const { pathname } = useLocation()
  const prefix = activePrefix ?? to
  const active = pathname === to || pathname.startsWith(prefix + '/') || pathname === prefix

  return (
    <Link
      to={to}
      title={collapsed ? label : undefined}
      className={cn(
        'group relative flex items-center gap-2.5 rounded-md px-3 py-2 text-sm transition-colors',
        collapsed ? 'mx-auto justify-center w-10 h-10' : 'w-full',
        active
          ? 'bg-selected font-medium text-primary-700'
          : 'text-muted hover:bg-hover hover:text-fg',
      )}
    >
      {active && <span className="absolute left-0 top-1/2 h-5 w-[3px] -translate-y-1/2 rounded-full bg-primary-600" />}
      <Icon className="size-4 shrink-0" />
      {!collapsed && <span className="flex-1 truncate">{label}</span>}
      {!collapsed && badge}
    </Link>
  )
}
