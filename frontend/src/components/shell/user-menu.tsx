import { useNavigate } from 'react-router'
import { LogOut, Settings, User as UserIcon, Sun, Moon } from 'lucide-react'
import { Avatar, AvatarFallback } from '@/components/ui/avatar'
import { Button } from '@/components/ui/button'
import { Badge } from '@/components/ui/badge'
import {
  DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuLabel,
  DropdownMenuSeparator, DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import { useAuth } from '@/hooks/use-auth'
import { useLogout } from '@/api/auth'
import { useTheme } from '@/hooks/use-theme'
import { ROLE } from '@/lib/constants'
import { ConfirmDialog } from '@/components/common/confirm-dialog'
import { useState } from 'react'

/** 用户头像下拉菜单（DS §5.2） */
export function UserMenu() {
  const { user, initials, username, isAdmin } = useAuth()
  const { resolved, toggle } = useTheme()
  const navigate = useNavigate()
  const logout = useLogout()
  const [confirmLogout, setConfirmLogout] = useState(false)

  if (!user) return null

  return (
    <>
      <DropdownMenu>
        <DropdownMenuTrigger asChild>
          <Button variant="ghost" size="icon" className="rounded-full" aria-label="用户菜单">
            <Avatar className="size-8">
              <AvatarFallback>{initials}</AvatarFallback>
            </Avatar>
          </Button>
        </DropdownMenuTrigger>
        <DropdownMenuContent align="end" className="w-60">
          <DropdownMenuLabel className="flex flex-col gap-0.5 pt-2">
            <span className="flex items-center gap-2 text-sm font-medium text-fg">
              {user.nickname || user.username}
              {isAdmin() && <Badge variant="brand" className="px-1.5 py-0">管理员</Badge>}
            </span>
            <span className="text-xs font-normal text-subtle">{user.email || username}</span>
          </DropdownMenuLabel>
          <DropdownMenuSeparator />
          <DropdownMenuItem onClick={() => void navigate('/app/account')}>
            <UserIcon /> 我的账号
          </DropdownMenuItem>
          {isAdmin() && (
            <DropdownMenuItem onClick={() => void navigate('/admin')}>
              <Settings /> 后台管理
            </DropdownMenuItem>
          )}
          <DropdownMenuItem onClick={toggle}>
            {resolved === 'dark' ? <Sun /> : <Moon />}
            切换主题
          </DropdownMenuItem>
          <DropdownMenuSeparator />
          <DropdownMenuItem variant="destructive" onClick={() => setConfirmLogout(true)}>
            <LogOut /> 退出登录
          </DropdownMenuItem>
        </DropdownMenuContent>
      </DropdownMenu>

      <ConfirmDialog
        open={confirmLogout}
        onOpenChange={setConfirmLogout}
        severity="warn"
        title="退出登录"
        description="将清除当前会话，你需要重新登录才能继续使用。"
        confirmText="退出"
        onConfirm={async () => {
          await logout.mutateAsync().catch(() => {})
          void navigate('/auth/login', { replace: true })
        }}
      />
    </>
  )
}

export { ROLE }
