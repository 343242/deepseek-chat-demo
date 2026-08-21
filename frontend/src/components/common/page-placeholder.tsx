import type { LucideIcon } from 'lucide-react'
import { EmptyState } from './empty-state'

/** 占位页（Phase J：teams/usage/account/admin 各页，保证整站可点通） */
export function PagePlaceholder({
  icon: Icon,
  title,
  description = '此页面将在后续迭代开发，当前为占位。',
  action,
}: {
  icon: LucideIcon
  title: string
  description?: string
  action?: React.ReactNode
}) {
  return (
    <div className="flex flex-1 items-center justify-center p-6">
      <EmptyState icon={<Icon />} title={title} description={description} action={action} />
    </div>
  )
}

/** 带标题栏的页面容器（后台表格密集页通用）。
 *  AppShell 的 <main> 为 overflow-hidden（聊天页内部自管滚动），长内容页由本容器自行滚动。 */
export function PageContainer({ title, subtitle, children, actions }: {
  title: string
  subtitle?: string
  children: React.ReactNode
  actions?: React.ReactNode
}) {
  return (
    <div className="mx-auto flex w-full max-w-[var(--layout-content-max-w)] flex-1 flex-col overflow-y-auto p-6">
      <div className="mb-6 flex items-start justify-between gap-4">
        <div>
          <h1 className="text-xl font-semibold text-fg">{title}</h1>
          {subtitle && <p className="mt-1 text-sm text-muted">{subtitle}</p>}
        </div>
        {actions}
      </div>
      {children}
    </div>
  )
}
