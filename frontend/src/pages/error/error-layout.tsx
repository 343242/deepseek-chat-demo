import { Link } from 'react-router'
import { Button } from '@/components/ui/button'
import { useAuth } from '@/hooks/use-auth'

/** ErrorShell（IA §3.4）—— 独立全屏，居中图标 + 标题 + 描述 + 行动按钮 */
export interface ErrorLayoutProps {
  icon: React.ReactNode
  title: string
  description: string
  actions?: React.ReactNode
}

export function ErrorLayout({ icon, title, description, actions }: ErrorLayoutProps) {
  const { isAuthenticated } = useAuth()
  return (
    <div className="flex min-h-screen flex-col items-center justify-center bg-canvas px-6 text-center">
      <div className="mb-6 flex size-20 items-center justify-center rounded-full bg-base text-neutral-300 [&_svg]:size-10">
        {icon}
      </div>
      <h1 className="text-2xl font-semibold text-fg">{title}</h1>
      <p className="mt-2 max-w-md text-muted">{description}</p>
      <div className="mt-8 flex gap-3">
        {actions ?? (
          <>
            {isAuthenticated && (
              <Button asChild>
                <Link to="/app/chat">返回聊天</Link>
              </Button>
            )}
            <Button variant="secondary" asChild>
              <Link to="/auth/login">退出登录</Link>
            </Button>
          </>
        )}
      </div>
    </div>
  )
}
