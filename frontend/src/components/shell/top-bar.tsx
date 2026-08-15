import { useNavigate } from 'react-router'
import { Settings, ArrowLeft } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Logo } from './logo'
import { ThemeToggle } from './theme-toggle'
import { UserMenu } from './user-menu'
import { usePermission } from '@/hooks/use-permission'
import { APP } from '@/lib/constants'

interface TopBarProps {
  /** 当前 shell：决定中间区显示"后台管理"还是"返回前台" */
  shell: 'app' | 'admin'
}

/** TopBar（DS §5.2 / IA §3）：56px，三段式（Logo / 前后台切换 / 主题+用户） */
export function TopBar({ shell }: TopBarProps) {
  const navigate = useNavigate()
  const { hasAnyAdminPermission } = usePermission()
  const showAdminEntry = hasAnyAdminPermission()

  return (
    <header
      className="flex h-14 shrink-0 items-center gap-3 border-b border-line bg-surface px-4"
      style={{ zIndex: 'var(--z-sticky)' }}
    >
      {/* 左：Logo + 产品名 */}
      <button
        className="flex items-center gap-2 outline-none focus-visible:shadow-focus rounded-md"
        onClick={() => void navigate(shell === 'admin' ? '/admin' : '/app/chat')}
      >
        <Logo size={28} />
        <span className="text-md font-semibold text-fg hidden sm:inline">{APP.name}</span>
      </button>

      <div className="flex-1" />

      {/* 中/右：前后台切换（仅 ADMIN 可见） */}
      {showAdminEntry &&
        (shell === 'app' ? (
          <Button variant="ghost" size="sm" onClick={() => void navigate('/admin')}>
            <Settings className="size-4" /> 后台管理
          </Button>
        ) : (
          <Button variant="ghost" size="sm" onClick={() => void navigate('/app/chat')}>
            <ArrowLeft className="size-4" /> 返回前台
          </Button>
        ))}

      <ThemeToggle />
      <UserMenu />
    </header>
  )
}
